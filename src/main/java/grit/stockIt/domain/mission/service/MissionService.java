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
     * (이벤트에는 ID가 포함되어 있으므로 findById 유지)
     * @param event 발생한 거래 이벤트
     */
    public void updateMissionProgress(TradeCompletionEvent event) {

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

                    log.info("미션 조건 일치: MemberId={}, MissionId={}, Condition={}",
                            member.getMemberId(), mission.getId(), mission.getConditionType());

                    int valueToIncrease = 0;
                    MissionConditionType type = mission.getConditionType();

                    if (type == MissionConditionType.BUY_COUNT ||
                            type == MissionConditionType.SELL_COUNT ||
                            type == MissionConditionType.TRADE_COUNT) {
                        valueToIncrease = 1;
                    } else if (type == MissionConditionType.BUY_AMOUNT ||
                            type == MissionConditionType.SELL_AMOUNT) {
                        valueToIncrease = event.getFilledAmount().intValue();
                    }

                    if (valueToIncrease > 0) {
                        progress.incrementProgress(valueToIncrease);
                        log.info("미션 진행도 갱신: MissionId={}, NewValue={}",
                                mission.getId(), progress.getCurrentValue());
                        checkMissionCompletion(progress);
                    }
                }
            }
        }
    }

    /**
     * [2] (내부) 미션 완료 여부를 검사하고 후속 조치 실행
     */
    public void checkMissionCompletion(MissionProgress progress) {
        if (progress.getStatus() == MissionStatus.COMPLETED || !progress.isCompleted()) {
            return;
        }

        progress.complete();
        log.info("미션 완료: MemberId={}, MissionId={}", progress.getMember().getMemberId(), progress.getMission().getId());

        distributeReward(progress.getMember(), progress.getMission().getReward());
        activateNextMission(progress);

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
     * 수정: memberId -> email 파라미터 변경
     */
    @Transactional
    public Reward claimDailyAttendance(String email) {
        Member member = getMemberByEmail(email);

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
     * 수정: memberId -> email 파라미터 변경
     */
    @Transactional(readOnly = true)
    public List<MissionProgress> getMissionProgressList(String email) {
        Member member = getMemberByEmail(email);
        return missionProgressRepository.findByMemberWithMissionAndReward(member);
    }

    /**
     * [신규] (API 호출) '종목 리포트 보기' 미션 처리
     * 수정: memberId -> email 파라미터 변경
     */
    @Transactional
    public void handleReportView(String email) {
        Member member = getMemberByEmail(email);

        // 1. '일일 리포트 조회' 미션 진행도 찾기
        MissionProgress reportProgress = findDailyMissionByConditionType(
                member, MissionConditionType.VIEW_REPORT
        );

        // 2. 이미 목표 달성(3회)했으면 갱신 안 함
        if (reportProgress.getStatus() == MissionStatus.COMPLETED ||
                reportProgress.isCompleted()) {
            log.info("이미 일일 리포트 미션을 완료했습니다: MemberId={}", member.getMemberId());
            return;
        }

        // 3. 진행도 증가
        reportProgress.incrementProgress(1);
        log.info("리포트 미션 진행도 갱신: MissionId={}, NewValue={}",
                reportProgress.getMission().getId(), reportProgress.getCurrentValue());

        // 4. 완료 여부 검사
        checkMissionCompletion(reportProgress);
    }

    /**
     * [신규] (API 호출) '포트폴리오 분석' 미션 처리
     * 수정: memberId -> email 파라미터 변경
     */
    @Transactional
    public void handlePortfolioAnalysis(String email) {
        Member member = getMemberByEmail(email);

        // 1. '일일 포트폴리오 분석' 미션 진행도 찾기
        MissionProgress analysisProgress = findDailyMissionByConditionType(
                member, MissionConditionType.ANALYZE_PORTFOLIO
        );

        // 2. 이미 목표 달성(1회)했으면 갱신 안 함
        if (analysisProgress.getStatus() == MissionStatus.COMPLETED ||
                analysisProgress.isCompleted()) {
            log.info("이미 일일 포트폴리오 분석 미션을 완료했습니다: MemberId={}", member.getMemberId());
            return;
        }

        // 3. 진행도 증가
        analysisProgress.incrementProgress(1);
        log.info("포트폴리오 분석 미션 진행도 갱신: MissionId={}, NewValue={}",
                analysisProgress.getMission().getId(), analysisProgress.getCurrentValue());

        // 4. 완료 여부 검사
        checkMissionCompletion(analysisProgress);
    }

    // --- Private Helper Methods ---

    /**
     * [Helper] 이메일로 회원 조회 (공통 로직 분리)
     */
    private Member getMemberByEmail(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다. Email: " + email));
    }

    private MissionProgress findDailyAttendanceMission(Member member) {
        return findDailyMissionByConditionType(member, MissionConditionType.LOGIN_COUNT);
    }

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
            case TRADE_COUNT:
                return true;
            default:
                return false;
        }
    }

    private boolean isTrackRelated(MissionTrack track, TradeCompletionEvent event) {
        return true;
    }

    private void handleMissionChain(MissionProgress completedProgress) {
        Member member = completedProgress.getMember();
        Mission mission = completedProgress.getMission();

        if (mission.getTrack() == MissionTrack.DAILY &&
                mission.getConditionType() == MissionConditionType.LOGIN_COUNT) {
            log.info("연쇄 업적 갱신 시도: 일일 출석 -> 연속 출석");
            updateSpecificAchievement(member, MissionConditionType.LOGIN_STREAK, 1);
        }
    }

    private void updateSpecificAchievement(Member member, MissionConditionType conditionType, int valueToIncrease) {
        Optional<Mission> achievementOpt = missionRepository
                .findByTrackAndConditionType(MissionTrack.ACHIEVEMENT, conditionType);

        if (achievementOpt.isEmpty()) {
            log.warn("연쇄 업적을 찾지 못했습니다: ConditionType={}", conditionType);
            return;
        }
        Mission achievement = achievementOpt.get();

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

        if (achievementProgress.getStatus() != MissionStatus.COMPLETED) {
            achievementProgress.incrementProgress(valueToIncrease);
            log.info("연쇄 업적 갱신: MissionId={}, NewValue={}",
                    achievement.getId(), achievementProgress.getCurrentValue());
            checkMissionCompletion(achievementProgress);
        }
    }

    private MissionProgress findDailyMissionByConditionType(Member member, MissionConditionType conditionType) {
        return missionProgressRepository
                .findByMemberAndMissionTypeWithMission(
                        member,
                        MissionTrack.DAILY,
                        conditionType
                )
                .orElseThrow(() -> new EntityNotFoundException(
                        "회원에게 [" + conditionType + "] 일일 미션이 존재하지 않습니다."
                ));
    }
}