package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.enums.MissionType;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * [B-6 잔류] 회원 액션 전담 서비스.
 * - 신규 회원 미션 초기화, 출석 보상, 파산 신청, 일일 단순 미션(리포트 조회·포트폴리오 분석) 처리.
 * - 거래 이벤트 진행도는 MissionProgressService, 자정 배치는 MissionBatchService,
 *   조회는 MissionQueryService, 완료 판정·보상은 MissionRewardService가 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional // 미션 관련 로직은 하나의 트랜잭션으로 관리
public class MissionService {

    // --- 의존성 주입 ---
    private final MemberRepository memberRepository;
    private final MissionRepository missionRepository;
    private final MissionProgressRepository missionProgressRepository;
    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository; // 파산 신청 시 보유 주식 평가용
    private final MissionTrackPolicy missionTrackPolicy;
    private final MissionRewardService missionRewardService;

    /**
     * [신규] 인생 2회차 (파산 신청) API 로직
     * - 조건: (보유 현금 + 보유 주식의 원금 총액) < 50,000원
     */
    @Transactional
    public Reward applyForBankruptcy(String email) {
        Member member = getMemberByEmail(email);
        // 1. 기본 계좌 조회
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member)
                .orElseThrow(() -> new EntityNotFoundException("기본 계좌가 없습니다."));

// 2. 해당 계좌의 보유 주식 목록 조회 (Repository 사용)
        // Account 엔티티에 accountStocks 리스트가 없으므로 리포지토리로 별도 조회
        List<AccountStock> myStocks = accountStockRepository.findAllByAccount(account);

        // 3. 보유 주식 총 평가금액 계산 (평단가 * 보유수량)
        // [수정] getBuyPrice() -> getAveragePrice()
        BigDecimal totalStockAsset = myStocks.stream()
                .map(as -> as.getAveragePrice().multiply(BigDecimal.valueOf(as.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. 총 자산 (현금 + 주식 원금)
        BigDecimal totalAsset = account.getCash().add(totalStockAsset);

        // 4. 100만원 미만인지 확인
        if (totalAsset.compareTo(BigDecimal.valueOf(1000000)) >= 0) {
            throw new IllegalStateException("아직 파산할 정도로 돈이 없지 않습니다. (자산: " + totalAsset + "원)");
        }

        // 5. 업적 달성 처리
        MissionProgress bankruptcyProgress = missionProgressRepository
                .findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.ASSET_UNDER_THRESHOLD)
                .orElseThrow(() -> new EntityNotFoundException("인생 2회차 미션 데이터를 찾을 수 없습니다."));

        if (bankruptcyProgress.isCompleted()) {
            throw new IllegalStateException("이미 구조 지원금을 받으셨습니다.");
        }

        bankruptcyProgress.setCurrentValue(bankruptcyProgress.getMission().getGoalValue()); // 조건 충족 표시
        bankruptcyProgress.complete();
        missionRewardService.distributeReward(member, bankruptcyProgress.getMission().getReward());

        log.info("파산 신청 승인! 구조지원금 지급 완료. Member={}", member.getName());

        // [추가] 2. 티어 점수 완전 초기화 (Bronze 0점으로 강등)
        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.ACTIVITY_SCORE)
                .ifPresent(p -> p.setCurrentValue(0));

        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.SKILL_SCORE)
                .ifPresent(p -> p.setCurrentValue(0));

        log.info("파산 승인 및 티어 초기화 완료: Member={}", member.getName());
        return bankruptcyProgress.getMission().getReward();
    }

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
            MissionStatus initialStatus = MissionStatus.INACTIVE;

            // 1. 초기값 설정 (기본 0)
            int initialValue = 0;

            // 🚨 [수정] 활동 점수 트래커(ACTIVITY_SCORE)는 1200점부터 시작 (Silver 1티어)
            if (mission.getConditionType() == MissionConditionType.ACTIVITY_SCORE) {
                initialValue = 1200;
            }

            // 1. 일일 미션 & 업적 미션 -> 기본 진행 중
            if (mission.getTrack() == MissionTrack.DAILY || mission.getTrack() == MissionTrack.ACHIEVEMENT) {
                initialStatus = MissionStatus.IN_PROGRESS;
            }
            // 2. 트랙 미션 -> 첫 번째 미션만 진행 중
            else if (missionTrackPolicy.isFirstMissionInTrack(mission.getId())) {
                initialStatus = MissionStatus.IN_PROGRESS;
            }

            MissionProgress newProgress = MissionProgress.builder()
                    .member(newMember)
                    .mission(mission)
                    .currentValue(initialValue)
                    .status(initialStatus)
                    .build();
            newMember.addMissionProgress(newProgress);
        }
        log.info("신규 회원 초기 미션 총 {}건 세팅 완료.", allMissions.size());
    }

    // --- API 관련 메서드 (Controller 호출용) ---

    @Transactional
    public Reward claimDailyAttendance(String email) {
        Member member = getMemberByEmail(email);
        MissionProgress attendanceProgress = missionProgressRepository
                .findByMemberAndMissionTypeWithMission(member, MissionTrack.DAILY, MissionConditionType.LOGIN_COUNT)
                .orElseThrow(() -> new EntityNotFoundException("일일 출석 미션을 찾을 수 없습니다."));

        if (attendanceProgress.getStatus() == MissionStatus.COMPLETED || attendanceProgress.isCompleted()) {
            throw new IllegalStateException("오늘은 이미 출석 보상을 받았습니다.");
        }

        attendanceProgress.incrementProgress(1);
        missionRewardService.checkMissionCompletion(attendanceProgress);
        return attendanceProgress.getMission().getReward();
    }

    @Transactional
    public void handleReportView(String email) {
        handleDailySimpleMission(email, MissionConditionType.VIEW_REPORT);
    }

    @Transactional
    public void handlePortfolioAnalysis(String email) {
        handleDailySimpleMission(email, MissionConditionType.ANALYZE_PORTFOLIO);
    }

    private void handleDailySimpleMission(String email, MissionConditionType type) {
        Member member = getMemberByEmail(email);
        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.DAILY, type)
                .ifPresent(progress -> {
                    if (progress.getStatus() != MissionStatus.COMPLETED && !progress.isCompleted()) {
                        progress.incrementProgress(1);
                        log.info("일일 미션({}) 진행도 갱신: MemberId={}", type, member.getMemberId());
                        missionRewardService.checkMissionCompletion(progress);
                    }
                });
    }

    // --- Helper Methods ---

    private Member getMemberByEmail(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다. Email: " + email));
    }

    // findDailyAttendanceMission 제거 (직접 쿼리 사용으로 대체됨)
}
