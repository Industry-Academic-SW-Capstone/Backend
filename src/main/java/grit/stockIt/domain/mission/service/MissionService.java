package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.enums.MissionType;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional // 미션 관련 로직은 하나의 트랜잭션으로 관리
public class MissionService {

    // --- 의존성 주입 (필요한 모든 리포지토리) ---
    private final MemberRepository memberRepository;
    private final MissionRepository missionRepository;
    private final MissionProgressRepository missionProgressRepository;
    private final MemberTitleRepository memberTitleRepository;
    private final AccountRepository accountRepository;

    /**
     * [1] (이벤트 수신) 거래 이벤트 발생 시 호출되는 메인 메서드
     * @param event 발생한 거래 이벤트
     */
    // @Async // (선택) 이벤트를 비동기로 처리하여 주문 트랜잭션과 분리
    public void updateMissionProgress(TradeCompletionEvent event) {

        // [수정] 이벤트 수신 로그 추가
        log.info("수신된 거래 이벤트: MemberId={}, Method={}, Qty={}",
                event.getMemberId(), event.getOrderMethod(), event.getFilledQuantity());

        Member member = memberRepository.findById(event.getMemberId())
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 1. 회원의 '진행 중'인 미션 목록 조회 (N+1 해결된 쿼리)
        List<MissionProgress> progressList = missionProgressRepository
                .findByMemberAndStatusWithMission(member, MissionStatus.IN_PROGRESS);

        // 2. 진행 중인 미션들을 순회하며 조건 검사
        for (MissionProgress progress : progressList) {
            Mission mission = progress.getMission();
            MissionTrack track = mission.getTrack();

            // 3. 트랙 필터링 (업적은 항상 검사)
            if (track == MissionTrack.ACHIEVEMENT || isTrackRelated(track, event)) {

                if (isMissionConditionMatches(mission, event)) {

                    // 4. [수정] 미션 조건 일치 로그 추가
                    log.info("미션 조건 일치: MemberId={}, MissionId={}, Condition={}",
                            member.getMemberId(), mission.getId(), mission.getConditionType());

                    int valueToIncrease = 0;
                    MissionConditionType type = mission.getConditionType();

                    // [수정] 모든 거래 관련 미션을 '1회'로 통일
                    if (type == MissionConditionType.BUY_COUNT ||
                            type == MissionConditionType.SELL_COUNT ||
                            type == MissionConditionType.TRADE_COUNT)
                    {
                        // "매수 1회", "매도 1회", "거래 1회" 모두
                        // '주문 완료 1건'을 의미하므로 1을 증가시킵니다.
                        valueToIncrease = 1;
                    }
                    else if (type == MissionConditionType.BUY_AMOUNT ||
                            type == MissionConditionType.SELL_AMOUNT)
                    {
                        // [주의] 이 로직은 이제 '마지막 체결 금액'만 반영합니다.
                        // 만약 '총 주문 금액'을 반영하려면 TradeCompletionEvent의
                        // 페이로드를 (order.getTotalAmount()) 등으로 수정해야 합니다.
                        valueToIncrease = event.getFilledAmount().intValue();
                    }
                    // ... (다른 조건들) ...

                    if (valueToIncrease > 0) {
                        progress.incrementProgress(valueToIncrease);
                        log.info("미션 진행도 갱신: MissionId={}, NewValue={}",
                                mission.getId(), progress.getCurrentValue());
                        checkMissionCompletion(progress); // 6. 완료 검사 (이 내부에서 handleMissionChain 호출)
                    }
                }
            }
        }
    }

    /**
     * [2] (내부) 미션 완료 여부를 검사하고 후속 조치 실행
     * @param progress 갱신된 미션 진행도
     */
    public void checkMissionCompletion(MissionProgress progress) {
        if (progress.getStatus() == MissionStatus.COMPLETED || !progress.isCompleted()) {
            return;
        }

        progress.complete();
        log.info("미션 완료: MemberId={}, MissionId={}", progress.getMember().getMemberId(), progress.getMission().getId());

        distributeReward(progress.getMember(), progress.getMission().getReward());
        activateNextMission(progress);

        // [수정] 완료된 미션에 따른 연쇄 업적 갱신
        handleMissionChain(progress);
    }

    /**
     * [3] (내부) 미션 완료에 따른 보상(돈, 칭호) 지급
     */
    private void distributeReward(Member member, Reward reward) {
        if (reward == null) return;

        if (reward.getMoneyAmount() > 0) {
            Account defaultAccount = accountRepository.findByMemberAndIsDefaultTrue(member)
                    .orElseThrow(() -> new EntityNotFoundException("보상을 지급할 기본 계좌를 찾을 수 없습니다."));
            defaultAccount.increaseCash(BigDecimal.valueOf(reward.getMoneyAmount()));
            log.info("보상 지급: {}원", reward.getMoneyAmount());
        }

        Title titleToGrant = reward.getTitleToGrant();
        if (titleToGrant != null) {
            boolean alreadyHas = memberTitleRepository.existsByMemberAndTitle(member, titleToGrant);
            if (!alreadyHas) {
                MemberTitle newMemberTitle = MemberTitle.builder()
                        .member(member)
                        .title(titleToGrant)
                        .build();
                member.addMemberTitle(newMemberTitle);
                log.info("칭호 지급: {}", titleToGrant.getName());
            }
        }
    }

    /**
     * [4] (내부) 다음 연계 미션 활성화 (중급/고급)
     */
    private void activateNextMission(MissionProgress completedProgress) {
        Mission completedMission = completedProgress.getMission();
        Member member = completedProgress.getMember();

        if (completedMission.getTrack() == MissionTrack.DAILY || completedMission.getTrack() == MissionTrack.ACHIEVEMENT) {
            return;
        }

        Mission nextMission = completedMission.getNextMission();

        if (nextMission != null) {
            log.info("다음 미션 활성화: MissionId={}", nextMission.getId());
            MissionProgress nextProgress = missionProgressRepository
                    .findByMemberAndMission(member, nextMission)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(nextMission)
                                .status(MissionStatus.INACTIVE)
                                .build();
                        member.addMissionProgress(newProgress);
                        return newProgress;
                    });
            nextProgress.activate();
        } else if (completedMission.getType() == MissionType.ADVANCED) {
            log.info("트랙 최종 완료: Track={}", completedMission.getTrack());
            resetMissionTrack(member, completedMission.getTrack());
        }
    }

    /**
     * [5] (내부) 특정 트랙(단타, 스윙, 장기) 초기화
     */
    public void resetMissionTrack(Member member, MissionTrack track) {
        log.info("트랙 초기화 시작: MemberId={}, Track={}", member.getMemberId(), track);
        List<MissionProgress> progressList = missionProgressRepository.findAllByMemberAndMission_Track(member, track);

        for (MissionProgress progress : progressList) {
            progress.deactivate();
            if (progress.getMission().getType() == MissionType.INTERMEDIATE) {
                progress.activate();
                log.info("트랙 1단계 미션 재활성화: MissionId={}", progress.getMission().getId());
            }
        }
    }

    /**
     * [6] (스케줄러 호출) 모든 '일일 미션' 갱신
     */
    @Transactional
    public void resetDailyMissions() {
        log.info("일일 미션 전체 초기화 시작...");
        List<MissionProgress> dailyProgressList = missionProgressRepository.findAllByMission_Track(MissionTrack.DAILY);
        for (MissionProgress progress : dailyProgressList) {
            progress.reset();
        }
        log.info("일일 미션 총 {}건 초기화 완료.", dailyProgressList.size());
    }

    /**
     * [7] (외부 호출) 신규 회원을 위한 초기 미션 세팅
     */
    @Transactional
    public void initializeMissionsForNewMember(Member newMember) {
        log.info("신규 회원 초기 미션 세팅 시작: MemberId={}", newMember.getMemberId());

        List<MissionTrack> tracks = Arrays.asList(MissionTrack.SHORT_TERM, MissionTrack.SWING, MissionTrack.LONG_TERM);
        List<Mission> dailyMissions = missionRepository.findAllByTrack(MissionTrack.DAILY);
        List<Mission> achievementMissions = missionRepository.findAllByTrack(MissionTrack.ACHIEVEMENT);
        List<Mission> intermediateMissions = missionRepository.findAllByTrackInAndType(tracks, MissionType.INTERMEDIATE);
        List<Mission> advancedMissions = missionRepository.findAllByTrackInAndType(tracks, MissionType.ADVANCED);

        List<Mission> allMissions = Stream.of(dailyMissions, achievementMissions, intermediateMissions, advancedMissions)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        for (Mission mission : allMissions) {
            MissionStatus initialStatus = (mission.getType() == MissionType.ADVANCED)
                    ? MissionStatus.INACTIVE
                    : MissionStatus.IN_PROGRESS;

            MissionProgress newProgress = MissionProgress.builder()
                    .member(newMember)
                    .mission(mission)
                    .currentValue(0)
                    .status(initialStatus)
                    .build();
            newMember.addMissionProgress(newProgress);
        }
        log.info("신규 회원 초기 미션 총 {}건 세팅 완료.", allMissions.size());
    }

    /**
     * [8] (API 호출) 일일 출석 체크 보상 수령
     */
    @Transactional
    public Reward claimDailyAttendance(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        MissionProgress attendanceProgress = findDailyAttendanceMission(member);

        if (attendanceProgress.getStatus() == MissionStatus.COMPLETED ||
                attendanceProgress.isCompleted()) {
            throw new IllegalStateException("오늘은 이미 출석 보상을 받았습니다.");
        }

        attendanceProgress.incrementProgress(1);
        checkMissionCompletion(attendanceProgress);
        return attendanceProgress.getMission().getReward();
    }

    /**
     * [9] (API 호출) 현재 회원의 모든 미션 진행 목록 조회
     */
    public List<MissionProgress> getMissionProgressList(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));
        return missionProgressRepository.findByMemberWithMissionAndReward(member);
    }

    /**
     * [신규] (API 호출) '종목 리포트 보기' 미션 처리
     */
    @Transactional
    public void handleReportView(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 1. '일일 리포트 조회' 미션 진행도 찾기
        MissionProgress reportProgress = findDailyMissionByConditionType(
                member, MissionConditionType.VIEW_REPORT
        );

        // 2. 이미 목표 달성(3회)했으면 갱신 안 함
        if (reportProgress.getStatus() == MissionStatus.COMPLETED ||
                reportProgress.isCompleted()) {
            log.info("이미 일일 리포트 미션을 완료했습니다: MemberId={}", memberId);
            return;
        }

        // 3. 진행도 증가 (예: 1/3 -> 2/3)
        reportProgress.incrementProgress(1);
        log.info("리포트 미션 진행도 갱신: MissionId={}, NewValue={}",
                reportProgress.getMission().getId(), reportProgress.getCurrentValue());

        // 4. 완료 여부 검사 (3/3이 되었는지)
        checkMissionCompletion(reportProgress);
    }

    /**
     * [신규] (API 호출) '포트폴리오 분석' 미션 처리
     */
    @Transactional
    public void handlePortfolioAnalysis(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 1. '일일 포트폴리오 분석' 미션 진행도 찾기
        MissionProgress analysisProgress = findDailyMissionByConditionType(
                member, MissionConditionType.ANALYZE_PORTFOLIO
        );

        // 2. 이미 목표 달성(1회)했으면 갱신 안 함
        if (analysisProgress.getStatus() == MissionStatus.COMPLETED ||
                analysisProgress.isCompleted()) {
            log.info("이미 일일 포트폴리오 분석 미션을 완료했습니다: MemberId={}", memberId);
            return;
        }

        // 3. 진행도 증가 (0/1 -> 1/1)
        analysisProgress.incrementProgress(1);
        log.info("포트폴리오 분석 미션 진행도 갱신: MissionId={}, NewValue={}",
                analysisProgress.getMission().getId(), analysisProgress.getCurrentValue());

        // 4. 완료 여부 검사 (1/1이 되었는지)
        checkMissionCompletion(analysisProgress);
    }

    /**
     * [Helper] 회원의 '일일 출석' 미션 진행도를 찾는 헬퍼
     */
    private MissionProgress findDailyAttendanceMission(Member member) {
        // [수정] 새로운 공용 헬퍼를 호출하도록 변경
        return findDailyMissionByConditionType(member, MissionConditionType.LOGIN_COUNT);
    }

    /**
     * [Helper] 이 미션이 이 이벤트로 갱신되어야 하는지 판별
     * [수정] TRADE_COUNT 추가
     */
    private boolean isMissionConditionMatches(Mission mission, TradeCompletionEvent event) {
        MissionConditionType missionType = mission.getConditionType();
        OrderMethod eventMethod = event.getOrderMethod();

        switch (missionType) {
            case BUY_COUNT:
            case BUY_AMOUNT:
                return eventMethod == OrderMethod.BUY;

            case SELL_COUNT:
            case SELL_AMOUNT:
                return eventMethod == OrderMethod.SELL;

            case TRADE_COUNT: // [수정] BUY/SELL 상관없이 거래이므로 true
                return true;

            default:
                // (LOGIN_COUNT 등)
                return false;
        }
    }

    /**
     * [Helper] 이 트랙이 이 이벤트와 연관이 있는지 판별
     */
    private boolean isTrackRelated(MissionTrack track, TradeCompletionEvent event) {
        // [임시 구현] 모든 트랙이 모든 이벤트에 반응
        return true;
    }

    /**
     * [Helper] [수정] 특정 미션 완료 시 연쇄 업적 갱신
     */
    private void handleMissionChain(MissionProgress completedProgress) {
        Member member = completedProgress.getMember();
        Mission mission = completedProgress.getMission();

        // [케이스 1] '일일 출석' 미션 완료 시 -> '연속 출석' 업적 갱신
        if (mission.getTrack() == MissionTrack.DAILY &&
                mission.getConditionType() == MissionConditionType.LOGIN_COUNT) {

            // (가정) '주식 중독'(ID 908) 업적의 conditionType = LOGIN_STREAK
            log.info("연쇄 업적 갱신 시도: 일일 출석 -> 연속 출석");
            updateSpecificAchievement(member, MissionConditionType.LOGIN_STREAK, 1);
        }

        // [케이스 2] '첫 수익' 관련
        // '첫 수익의 기쁨'(ID 902) 업적은 'updateMissionProgress'에서
        // (condition_type = SELL_COUNT, goal_value = 1)로 설정되어
        // 자동으로 처리되도록 하는 것이 좋습니다. (아래 2-1 참고)
    }

    /**
     * [Helper] (handleMissionChain에서 사용될) 특정 업적 갱신
     * (conditionType으로 업적을 찾아 갱신)
     */
    private void updateSpecificAchievement(Member member, MissionConditionType conditionType, int valueToIncrease) {
        // 1. 해당 조건의 '업적' 미션 찾기
        Optional<Mission> achievementOpt = missionRepository
                .findByTrackAndConditionType(MissionTrack.ACHIEVEMENT, conditionType);

        if (achievementOpt.isEmpty()) {
            log.warn("연쇄 업적을 찾지 못했습니다: ConditionType={}", conditionType);
            return;
        }
        Mission achievement = achievementOpt.get();

        // 2. 해당 업적의 '진행도' 찾기 (FindOrCreate)
        MissionProgress achievementProgress = missionProgressRepository
                .findByMemberAndMission(member, achievement)
                .orElseGet(() -> {
                    MissionProgress newProgress = MissionProgress.builder()
                            .member(member)
                            .mission(achievement)
                            .status(MissionStatus.IN_PROGRESS)
                            .build();
                    member.addMissionProgress(newProgress);
                    return newProgress;
                });

        // 3. 갱신 및 완료 검사 (재귀적)
        if (achievementProgress.getStatus() != MissionStatus.COMPLETED) {
            achievementProgress.incrementProgress(valueToIncrease);
            log.info("연쇄 업적 갱신: MissionId={}, NewValue={}",
                    achievement.getId(), achievementProgress.getCurrentValue());
            checkMissionCompletion(achievementProgress);
        }
    }
    /**
     * [신규] '일일 미션'을 ConditionType으로 찾는 공용 헬퍼
     */
    private MissionProgress findDailyMissionByConditionType(Member member, MissionConditionType conditionType) {
        // MissionProgressRepository의 쿼리 메서드 재사용
        return missionProgressRepository
                .findByMemberAndMissionTypeWithMission(
                        member,
                        MissionTrack.DAILY, // 일일 미션
                        conditionType       // 찾으려는 조건 (LOGIN_COUNT, VIEW_REPORT 등)
                )
                .orElseThrow(() -> new EntityNotFoundException(
                        "회원에게 [" + conditionType + "] 일일 미션이 존재하지 않습니다."
                ));
    }
}