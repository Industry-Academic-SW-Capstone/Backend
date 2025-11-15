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
import grit.stockIt.domain.order.event.TradeCompletionEvent; // 1. [수정] 새로운 이벤트 임포트
import grit.stockIt.domain.account.entity.Account; // 2. [수정] 팀원의 Account 엔티티 임포트
import grit.stockIt.domain.account.repository.AccountRepository; // 3. [신규] AccountRepository 주입
import java.math.BigDecimal; // 4. [신규] BigDecimal 임포트


import java.util.Arrays; // [수정] 임포트 추가
import java.util.List;
import java.util.stream.Collectors; // [수정] 임포트 추가
import java.util.stream.Stream; // [수정] 임포트 추가

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
    public void updateMissionProgress(TradeCompletionEvent event) {
        Member member = memberRepository.findById(event.getMemberId())
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 1. 회원의 '진행 중'인 미션 목록 조회
        List<MissionProgress> progressList = missionProgressRepository.findByMemberAndStatus(member, MissionStatus.IN_PROGRESS);

        // 2. 진행 중인 미션들을 순회하며 조건 검사
        for (MissionProgress progress : progressList) {
            Mission mission = progress.getMission();
            MissionTrack track = mission.getTrack();

            // 3. 트랙 필터링 (업적은 항상 검사, 나머지는 이벤트와 트랙이 연관되어야 함)
            if (track == MissionTrack.ACHIEVEMENT || isTrackRelated(track, event)) {

                if (isMissionConditionMatches(mission, event)) {

                    // 7. [수정] 이벤트에서 실제 체결 수량/금액을 가져오도록 수정
                    int valueToIncrease = 0;
                    MissionConditionType type = mission.getConditionType();

                    if (type == MissionConditionType.BUY_COUNT || type == MissionConditionType.SELL_COUNT) {
                        valueToIncrease = event.getFilledQuantity(); // (또는 1회로 카운트)
                    } else if (type == MissionConditionType.BUY_AMOUNT || type == MissionConditionType.SELL_AMOUNT) {
                        valueToIncrease = event.getFilledAmount().intValue(); // (BigDecimal to int)
                    }
                    // ... (다른 조건들) ...

                    progress.incrementProgress(valueToIncrease);
                    checkMissionCompletion(progress);
                }
            }
        }
    }

    /**
     * [2] (내부) 미션 완료 여부를 검사하고 후속 조치 실행
     * @param progress 갱신된 미션 진행도
     */
    public void checkMissionCompletion(MissionProgress progress) {
        // 이미 완료 상태거나, 목표 미달이면 리턴
        if (progress.getStatus() == MissionStatus.COMPLETED || !progress.isCompleted()) {
            return;
        }

        // 1. 미션 완료 처리
        progress.complete();
        log.info("미션 완료: MemberId={}, MissionId={}", progress.getMember().getMemberId(), progress.getMission().getId());

        // 2. 보상 지급
        distributeReward(progress.getMember(), progress.getMission().getReward());

        // 3. 다음 연계 미션 활성화 (또는 트랙 초기화)
        activateNextMission(progress);

        // 4. [하드코딩] 완료된 미션에 따른 연쇄 업적 갱신
        handleMissionChain(progress);
    }

    /**
     * [3] (내부) 미션 완료에 따른 보상(돈, 칭호) 지급
     */
    private void distributeReward(Member member, Reward reward) {
        if (reward == null) return;

        // 1. [수정] 금액 보상 지급
        if (reward.getMoneyAmount() > 0) {
            // [가정] 보상은 '기본' 계좌로 들어간다.
            // (LocalMemberService가 '기본 계좌'를 생성해준다고 가정)
            Account defaultAccount = accountRepository.findByMemberAndIsDefaultTrue(member) // (이런 메서드가 AccountRepository에 필요)
                    .orElseThrow(() -> new EntityNotFoundException("보상을 지급할 기본 계좌를 찾을 수 없습니다."));

            // [수정] long이 아닌 BigDecimal로 입금
            defaultAccount.increaseCash(BigDecimal.valueOf(reward.getMoneyAmount()));

            log.info("보상 지급: {}원", reward.getMoneyAmount());
        }

        // 2. 칭호 보상 지급
        Title titleToGrant = reward.getTitleToGrant();
        if (titleToGrant != null) {
            // 이미 보유한 칭호인지 확인
            boolean alreadyHas = memberTitleRepository.existsByMemberAndTitle(member, titleToGrant);
            if (!alreadyHas) {
                MemberTitle newMemberTitle = MemberTitle.builder()
                        .member(member)
                        .title(titleToGrant)
                        .build();
                member.addMemberTitle(newMemberTitle); // Member의 편의 메서드로 양방향 관계 설정
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

        // 일일 미션, 업적은 연계 없음
        if (completedMission.getTrack() == MissionTrack.DAILY || completedMission.getTrack() == MissionTrack.ACHIEVEMENT) {
            return;
        }

        Mission nextMission = completedMission.getNextMission();

        if (nextMission != null) {
            // [Case 1] 다음 미션이 있는 경우 (예: 중급 1 -> 중급 2, 고급 2 -> 고급 3)
            log.info("다음 미션 활성화: MissionId={}", nextMission.getId());

            // 다음 미션에 대한 MissionProgress를 찾거나 새로 생성(FindOrCreate)
            MissionProgress nextProgress = missionProgressRepository
                    .findByMemberAndMission(member, nextMission)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(nextMission)
                                .status(MissionStatus.INACTIVE) // 초기 상태는 INACTIVE
                                .build();
                        member.addMissionProgress(newProgress); // 연관관계 설정
                        return newProgress;
                    });

            nextProgress.activate(); // 'IN_PROGRESS'로 상태 변경

        } else if (completedMission.getType() == MissionType.ADVANCED) {
            // [Case 2] 다음 미션이 없고, 현재 미션이 '고급' = 트랙 최종 완료
            log.info("트랙 최종 완료: Track={}", completedMission.getTrack());
            resetMissionTrack(member, completedMission.getTrack());
        }
    }

    /**
     * [5] (내부) 특정 트랙(단타, 스윙, 장기) 초기화
     * [수정] 상세 로직 구현
     */
    public void resetMissionTrack(Member member, MissionTrack track) {
        log.info("트랙 초기화 시작: MemberId={}, Track={}", member.getMemberId(), track);

        // 1. 해당 트랙의 모든 (중급/고급) MissionProgress 조회
        List<MissionProgress> progressList = missionProgressRepository.findAllByMemberAndMission_Track(member, track);

        // 2. 모든 미션을 '비활성' 및 0으로 초기화
        for (MissionProgress progress : progressList) {
            progress.deactivate(); // INACTIVE, currentValue = 0

            // 3. '중급 1단계'(INTERMEDIATE) 미션만 다시 활성화
            // (가정: INTERMEDIATE 타입은 모두 1단계 미션)
            if (progress.getMission().getType() == MissionType.INTERMEDIATE) {
                progress.activate(); // IN_PROGRESS
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
        // 1. 모든 '일일 미션'의 '진행도' 객체 조회
        List<MissionProgress> dailyProgressList = missionProgressRepository.findAllByMission_Track(MissionTrack.DAILY);

        // 2. 모든 진행도를 0, IN_PROGRESS 상태로 리셋
        for (MissionProgress progress : dailyProgressList) {
            progress.reset();
        }
        log.info("일일 미션 총 {}건 초기화 완료.", dailyProgressList.size());
    }

    /**
     * [7] (외부 호출) 신규 회원을 위한 초기 미션 세팅
     * [신규] 메서드 추가
     * @param newMember 새로 가입한 회원 엔티티
     */
    @Transactional
    public void initializeMissionsForNewMember(Member newMember) {
        log.info("신규 회원 초기 미션 세팅 시작: MemberId={}", newMember.getMemberId());

        // 1. 모든 '일일 미션' 조회
        List<Mission> dailyMissions = missionRepository.findAllByTrack(MissionTrack.DAILY);

        // 2. 모든 '업적 미션' 조회
        List<Mission> achievementMissions = missionRepository.findAllByTrack(MissionTrack.ACHIEVEMENT);

        // 3. 모든 '중급 1단계' 미션 조회 (단타, 스윙, 장기)
        List<MissionTrack> tracks = Arrays.asList(MissionTrack.SHORT_TERM, MissionTrack.SWING, MissionTrack.LONG_TERM);
        List<Mission> intermediateMissions = missionRepository.findAllByTrackInAndType(tracks, MissionType.INTERMEDIATE);

        // 4. (1+2+3) 모든 초기 미션을 하나의 리스트로 합침
        List<Mission> initialMissions = Stream.of(dailyMissions, achievementMissions, intermediateMissions)
                .flatMap(List::stream)
                .collect(Collectors.toList());

        // 5. 각 미션에 대한 MissionProgress 생성
        for (Mission mission : initialMissions) {
            // 요구사항: 일일, 업적, 중급 1단계는 '전부 활성화'
            MissionProgress newProgress = MissionProgress.builder()
                    .member(newMember)
                    .mission(mission)
                    .currentValue(0)
                    .status(MissionStatus.IN_PROGRESS) // 즉시 '진행 중' 상태로 활성화
                    .build();

            // Member의 편의 메서드를 통해 양방향 관계 설정 및 Cascade 저장
            newMember.addMissionProgress(newProgress);
        }
        log.info("신규 회원 초기 미션 총 {}건 세팅 완료.", initialMissions.size());
    }

    /**
     * [8] (API 호출) 일일 출석 체크 보상 수령
     * @param memberId 로그인한 회원 ID
     * @return 획득한 보상 객체 (Controller에게 반환)
     */
    @Transactional
    public Reward claimDailyAttendance(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // 1. 회원의 '진행 중'인 미션 중 '일일 출석 미션' 찾기
        // (LOGIN_COUNT는 '일일 출석 버튼 클릭' 타입으로 간주)
        MissionProgress attendanceProgress = findDailyAttendanceMission(member);

        // 2. 이미 완료(클릭)했는지 확인
        if (attendanceProgress.getStatus() == MissionStatus.COMPLETED ||
                attendanceProgress.isCompleted()) {
            throw new IllegalStateException("오늘은 이미 출석 보상을 받았습니다.");
        }

        // 3. 진행도 증가 (0 -> 1)
        attendanceProgress.incrementProgress(1);

        // 4. [중요] 기존 완료 검사 로직 재사용
        // -> 이 메서드가 (COMPLETED 변경, 보상 지급, 7일 업적 연쇄)를 모두 처리
        checkMissionCompletion(attendanceProgress);

        // 5. 완료된 미션의 보상 정보 반환
        return attendanceProgress.getMission().getReward();
    }

    /**
     * [9] (API 호출) 현재 회원의 모든 미션 진행 목록 조회
     * (N+1 방지를 위해 Fetch Join 사용)
     * @param memberId 회원 ID
     * @return DTO로 변환될 엔티티 리스트
     */
    public List<MissionProgress> getMissionProgressList(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));

        // N+1이 해결된 쿼리 메서드 호출
        return missionProgressRepository.findByMemberWithMissionAndReward(member);
    }

    /**
     * [Helper] 회원의 '일일 출석' 미션 진행도를 찾는 헬퍼
     */
    private MissionProgress findDailyAttendanceMission(Member member) {
        List<MissionProgress> progressList = missionProgressRepository
                .findByMemberAndStatus(member, MissionStatus.IN_PROGRESS);

        for (MissionProgress progress : progressList) {
            Mission mission = progress.getMission();
            // (LOGIN_COUNT를 '일일 출석' 조건으로 사용)
            if (mission.getTrack() == MissionTrack.DAILY &&
                    mission.getConditionType() == MissionConditionType.LOGIN_COUNT) {
                return progress;
            }
        }
        // (만약 IN_PROGRESS에서 못찾았다면, COMPLETED 상태인지 확인하거나 예외 처리)
        // (여기서는 스케줄러가 매일 IN_PROGRESS로 만든다고 가정)
        throw new EntityNotFoundException("활성화된 일일 출석 미션을 찾을 수 없습니다.");
    }

    // --- ⬇️ (구현 필요) 헬퍼 메서드들 ⬇️ ---

    /**
     * [Helper] 이 미션이 이 이벤트로 갱신되어야 하는지 판별
     * [수정] 이벤트 객체의 속성을 기반으로 미션 조건과 일치하는지 확인
     */
    private boolean isMissionConditionMatches(Mission mission, TradeCompletionEvent event) {
        // 1. 미션이 원하는 조건 타입을 가져옴
        MissionConditionType missionType = mission.getConditionType();

        // 2. 실제 발생한 이벤트의 속성을 가져옴
        OrderMethod eventMethod = event.getOrderMethod(); // BUY 또는 SELL

        // 3. 미션 조건(missionType)에 따라 이벤트 속성(eventMethod)이 맞는지 검사
        switch (missionType) {
            case BUY_COUNT:
            case BUY_AMOUNT:
                // 이 미션들은 '매수(BUY)' 이벤트에만 반응해야 함
                return eventMethod == OrderMethod.BUY;

            case SELL_COUNT:
            case SELL_AMOUNT:
                // 이 미션들은 '매도(SELL)' 이벤트에만 반응해야 함
                return eventMethod == OrderMethod.SELL;

            // (만약 '총 거래 횟수' 미션이 있다면)
            // case TRADE_COUNT:
            //    return true; // 매수/매도 상관없이 무조건 참

            default:
                // 이 미션(예: LOGIN_COUNT)은 거래(Trade) 이벤트와 관련 없음
                return false;
        }
    }

    /**
     * [Helper] 이 트랙이 이 이벤트와 연관이 있는지 판별
     */
    private boolean isTrackRelated(MissionTrack track, TradeCompletionEvent event) {
        // (구현) 이벤트의 성격(단타성, 장기성)을 판별하여 트랙과 비교
        // 예: (track == MissionTrack.SHORT_TERM && event.isShortTermEvent())

        // [임시 구현] 모든 트랙이 모든 이벤트에 반응
        return true;
    }

    /**
     * [Helper] (하드코딩) 특정 미션 완료 시 연쇄 업적 갱신
     */
    private void handleMissionChain(MissionProgress completedProgress) {
        // [케이스 1] '일일 출석' 미션 완료 시 -> '7일 출석' 업적 갱신
        // if (completedProgress.getMission().getId() == '일일출석_ID') {
        //     updateSpecificAchievementProgress(completedProgress.getMember(), '7일출석_ID', 1);
        // }

        // [케이스 2] '트랙 최종 완료' 시 -> (activateNextMission에서 이미 처리됨)
    }

    /**
     * [Helper] (handleMissionChain에서 사용될) 특정 업적 갱신
     * (케이스가 2개 뿐이라 handleMissionChain 내부에 바로 구현해도 무방)
     */
    /*
    private void updateSpecificAchievementProgress(Member member, long achievementMissionId, int valueToIncrease) {
        Mission achievement = missionRepository.findById(achievementMissionId)
            .orElseThrow(() -> new EntityNotFoundException("업적을 찾을 수 없습니다."));

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
            checkMissionCompletion(achievementProgress); // 업적 완료 여부 재귀적 체크
        }
    }
    */
}