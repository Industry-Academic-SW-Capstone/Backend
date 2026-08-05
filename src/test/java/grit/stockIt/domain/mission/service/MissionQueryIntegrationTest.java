package grit.stockIt.domain.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.service.AccountService;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.dto.MemberTitleResponse;
import grit.stockIt.domain.mission.dto.MissionDashboardResponse;
import grit.stockIt.domain.mission.dto.MissionListResponse;
import grit.stockIt.domain.mission.dto.UserTierStatusResponse;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * [특성화 테스트] 미션 조회/API 계약(AC8 근거)의 현재 응답 값을 그대로 고정한다.
 * B-2 이후 조회 4종(getMissionDashboard/getMissionsByTrack/getMyTitles/getTierInfo)은
 * MissionQueryService를, 쓰기 액션(claimDailyAttendance/applyForBankruptcy)은 MissionService를 표적한다.
 * 리팩토링 전 안전망이므로 "이상해 보이는" 동작(잘못된 track -> 빈 목록, 파산 기준 100만원 등)도
 * 관찰된 그대로 단언하고 수정하지 않는다.
 */
@Transactional
@Sql("/data.sql")
class MissionQueryIntegrationTest extends IntegrationTestSupport {

    private static final long DAILY_ATTENDANCE_ID = 101L;
    private static final long SEED_COPIER_ID = 901L;
    private static final long STREAK_TRACKER_ID = 900L;
    private static final long STREAK_7D_ID = 906L;
    private static final long BANKRUPTCY_ID = 911L;
    private static final long ACTIVITY_SCORE_TRACKER_ID = 998L;
    private static final long SKILL_SCORE_TRACKER_ID = 999L;

    @Autowired
    private MissionService missionService;

    @Autowired
    private MissionQueryService missionQueryService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private MissionProgressRepository missionProgressRepository;

    // --- 4. getMissionDashboard ---

    @Test
    @DisplayName("신규 회원의 대시보드: 연속 출석 0일, 남은 일일 미션 4개")
    void getMissionDashboard_신규회원은_연속출석0_남은일일미션4() {
        Member member = createMemberWithMissions();

        MissionDashboardResponse response = missionQueryService.getMissionDashboard(member.getEmail());

        assertThat(response.getConsecutiveAttendanceDays()).isZero();
        assertThat(response.getRemainingDailyMissions()).isEqualTo(4);
    }

    @Test
    @DisplayName("출석 완료 후 대시보드: 연속 출석 1일(트래커 900 기준), 남은 일일 미션 3개")
    void getMissionDashboard_출석완료후_연속출석1_남은미션3() {
        Member member = createMemberWithMissions();
        missionService.claimDailyAttendance(member.getEmail());

        MissionDashboardResponse response = missionQueryService.getMissionDashboard(member.getEmail());

        // 특성화: goalValue가 가장 큰 LOGIN_STREAK 미션(900, 목표 100,000)의 현재값이 연속 출석 일수
        assertThat(response.getConsecutiveAttendanceDays()).isEqualTo(1);
        assertThat(response.getRemainingDailyMissions()).isEqualTo(3);
    }

    // --- 5. getMissionsByTrack ---

    @Test
    @DisplayName("track=ALL 또는 null이면 회원의 전체 미션 진행도 29건을 반환한다")
    void getMissionsByTrack_ALL과_null은_전체_29건을_반환한다() {
        Member member = createMemberWithMissions();

        // 특성화: data.sql 기준 DAILY 4 + ACHIEVEMENT 13(트래커 900/998/999 포함) + 트랙 12 = 29
        assertThat(missionQueryService.getMissionsByTrack(member.getEmail(), "ALL")).hasSize(29);
        assertThat(missionQueryService.getMissionsByTrack(member.getEmail(), "all")).hasSize(29);
        assertThat(missionQueryService.getMissionsByTrack(member.getEmail(), null)).hasSize(29);
    }

    @Test
    @DisplayName("track=DAILY 필터링과 응답 필드 값 고정 (출석체크 미션 101)")
    void getMissionsByTrack_DAILY_필터와_응답필드값() {
        Member member = createMemberWithMissions();

        List<MissionListResponse> daily = missionQueryService.getMissionsByTrack(member.getEmail(), "DAILY");

        assertThat(daily).hasSize(4);
        assertThat(daily).allMatch(m -> "DAILY".equals(m.getTrack()));

        MissionListResponse attendance = daily.stream()
                .filter(m -> m.getId() == DAILY_ATTENDANCE_ID)
                .findFirst().orElseThrow();
        assertThat(attendance.getTitle()).isEqualTo("출석체크하기");
        // 특성화: ACHIEVEMENT가 아니면 description은 미션 이름 그대로
        assertThat(attendance.getDescription()).isEqualTo("출석체크하기");
        assertThat(attendance.getCurrentValue()).isZero();
        assertThat(attendance.getGoalValue()).isEqualTo(1);
        assertThat(attendance.isCompleted()).isFalse();
        assertThat(attendance.getRewardMoney()).isEqualTo(150000L);
        assertThat(attendance.getRewardTitle()).isNull();
    }

    @Test
    @DisplayName("track=ACHIEVEMENT 필터: 13건, 칭호 설명 매핑 및 보상 없는 트래커 미션 응답 고정")
    void getMissionsByTrack_ACHIEVEMENT_필터와_칭호설명_매핑() {
        Member member = createMemberWithMissions();

        List<MissionListResponse> achievements = missionQueryService.getMissionsByTrack(member.getEmail(), "ACHIEVEMENT");

        assertThat(achievements).hasSize(13);

        // 특성화: ACHIEVEMENT + 칭호 보상이면 description은 칭호 설명으로 대체
        MissionListResponse seedCopier = achievements.stream()
                .filter(m -> m.getId() == SEED_COPIER_ID)
                .findFirst().orElseThrow();
        assertThat(seedCopier.getTitle()).isEqualTo("시드 콜로니");
        assertThat(seedCopier.getDescription()).isEqualTo("누적 미션 30회 완료한 자");
        assertThat(seedCopier.getRewardMoney()).isEqualTo(15000000L);
        assertThat(seedCopier.getRewardTitle()).isEqualTo("시드 콜로니");

        // 특성화: 활동 점수 트래커(998)도 목록에 노출됨 (초기값 1200, 보상 없음)
        MissionListResponse activityTracker = achievements.stream()
                .filter(m -> m.getId() == ACTIVITY_SCORE_TRACKER_ID)
                .findFirst().orElseThrow();
        assertThat(activityTracker.getDescription()).isEqualTo("활동 점수 트래커");
        assertThat(activityTracker.getCurrentValue()).isEqualTo(1200);
        assertThat(activityTracker.getRewardMoney()).isZero();
        assertThat(activityTracker.getRewardTitle()).isNull();
    }

    @Test
    @DisplayName("소문자 트랙명(daily)도 대문자로 변환되어 필터링된다")
    void getMissionsByTrack_소문자_트랙명도_필터링된다() {
        Member member = createMemberWithMissions();

        assertThat(missionQueryService.getMissionsByTrack(member.getEmail(), "daily")).hasSize(4);
    }

    @Test
    @DisplayName("정의되지 않은 track 문자열이면 예외 없이 빈 목록을 반환한다")
    void getMissionsByTrack_잘못된_트랙명은_빈목록을_반환한다() {
        Member member = createMemberWithMissions();

        // 특성화: MissionQueryService.getMissionsByTrack — IllegalArgumentException을 삼키고 List.of() 반환, 버그 의심
        assertThat(missionQueryService.getMissionsByTrack(member.getEmail(), "NOT_A_TRACK")).isEmpty();
    }

    // --- 6. getMyTitles / getTierInfo / claimDailyAttendance / applyForBankruptcy ---

    @Test
    @DisplayName("신규 회원의 보유 칭호는 빈 목록이다")
    void getMyTitles_신규회원은_빈목록() {
        Member member = createMemberWithMissions();

        assertThat(missionQueryService.getMyTitles(member.getEmail())).isEmpty();
    }

    @Test
    @DisplayName("신규 회원의 티어: 활동 1200 + 실력 0 = SILVER 1, 다음 티어까지 200점, 진행도 0%")
    void getTierInfo_신규회원은_활동점수1200으로_SILVER1() {
        Member member = createMemberWithMissions();

        UserTierStatusResponse tier = missionQueryService.getTierInfo(member.getEmail());

        // 특성화: 활동 점수 트래커(998) 초기값 1200으로 신규 회원은 SILVER 1에서 시작
        assertThat(tier.getCurrentTier()).isEqualTo("SILVER 1");
        assertThat(tier.getNextTier()).isEqualTo("SILVER 2");
        assertThat(tier.getActivityScore()).isEqualTo(1200);
        assertThat(tier.getSkillScore()).isZero();
        assertThat(tier.getTotalScore()).isEqualTo(1200);
        assertThat(tier.getScoreToNextTier()).isEqualTo(200);
        assertThat(tier.getProgressPercentage()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("실력 점수는 누적 수익금(999 트래커)의 sqrt(profit/10)로 환산되고, 손실이면 0점이다")
    void getTierInfo_실력점수는_수익금의_제곱근으로_환산된다() {
        Member member = createMemberWithMissions();
        progressOf(member, SKILL_SCORE_TRACKER_ID).setCurrentValue(1000);
        missionProgressRepository.flush();

        UserTierStatusResponse tier = missionQueryService.getTierInfo(member.getEmail());

        // 특성화: sqrt(1000 / 10) = 10점
        assertThat(tier.getSkillScore()).isEqualTo(10);
        assertThat(tier.getTotalScore()).isEqualTo(1210);
        assertThat(tier.getCurrentTier()).isEqualTo("SILVER 1");
        assertThat(tier.getScoreToNextTier()).isEqualTo(190);
        assertThat(tier.getProgressPercentage()).isEqualTo(5.0);

        // 누적 손실이면 실력 점수 0
        progressOf(member, SKILL_SCORE_TRACKER_ID).setCurrentValue(-500);
        missionProgressRepository.flush();
        assertThat(missionQueryService.getTierInfo(member.getEmail()).getSkillScore()).isZero();
    }

    @Test
    @DisplayName("총점 3600 이상이면 LEGEND: nextTier=MAX, 남은 점수 0, 진행도 100%")
    void getTierInfo_LEGEND_도달시_nextTier_MAX_진행도100() {
        Member member = createMemberWithMissions();
        progressOf(member, ACTIVITY_SCORE_TRACKER_ID).setCurrentValue(3600);
        missionProgressRepository.flush();

        UserTierStatusResponse tier = missionQueryService.getTierInfo(member.getEmail());

        assertThat(tier.getCurrentTier()).isEqualTo("LEGEND");
        assertThat(tier.getNextTier()).isEqualTo("MAX");
        assertThat(tier.getTotalScore()).isEqualTo(3600);
        // 특성화: LEGEND는 목표치를 현재 점수와 동일시하여 남은 점수 0
        assertThat(tier.getScoreToNextTier()).isZero();
        assertThat(tier.getProgressPercentage()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("출석 보상 수령: 15만원 지급·미션 완료·연속 출석 연쇄 갱신·활동 점수 +10")
    void claimDailyAttendance_보상지급과_연쇄효과를_고정한다() {
        Member member = createMemberWithMissions();
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member).orElseThrow();
        BigDecimal cashBefore = account.getCash();

        Reward reward = missionService.claimDailyAttendance(member.getEmail());

        assertThat(reward.getMoneyAmount()).isEqualTo(150000L);
        assertThat(reward.getTitleToGrant()).isNull();
        assertThat(account.getCash()).isEqualByComparingTo(cashBefore.add(BigDecimal.valueOf(150000)));

        MissionProgress attendance = progressOf(member, DAILY_ATTENDANCE_ID);
        assertThat(attendance.getCurrentValue()).isEqualTo(1);
        assertThat(attendance.getStatus()).isEqualTo(MissionStatus.COMPLETED);

        // 연쇄: LOGIN_STREAK 계열(트래커 900 포함) +1
        assertThat(progressOf(member, STREAK_TRACKER_ID).getCurrentValue()).isEqualTo(1);
        assertThat(progressOf(member, STREAK_7D_ID).getCurrentValue()).isEqualTo(1);

        // 연쇄: 시드 콜로니(901) 진행도 = 완료 미션 수(1)
        assertThat(progressOf(member, SEED_COPIER_ID).getCurrentValue()).isEqualTo(1);

        // 특성화: 미션 완료 시 활동 점수 트래커(998) 1200 -> 1210
        assertThat(progressOf(member, ACTIVITY_SCORE_TRACKER_ID).getCurrentValue()).isEqualTo(1210);
    }

    @Test
    @DisplayName("출석 보상을 이미 받았으면 IllegalStateException이 발생한다")
    void claimDailyAttendance_중복호출시_예외가_발생한다() {
        Member member = createMemberWithMissions();
        missionService.claimDailyAttendance(member.getEmail());

        assertThatThrownBy(() -> missionService.claimDailyAttendance(member.getEmail()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("오늘은 이미 출석 보상을 받았습니다.");
    }

    @Test
    @DisplayName("총 자산 100만원 이상이면 파산 신청이 거절된다 (API 문구는 5만원이지만 코드는 100만원 기준)")
    void applyForBankruptcy_자산이_100만원_이상이면_예외가_발생한다() {
        Member member = createMemberWithMissions();
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member).orElseThrow();
        // 특성화: 경계값 100만원 정확히 보유해도 거절됨 (>= 비교). 칭호/스웨거 설명(5만원)과 불일치, 버그 의심
        account.setCash(BigDecimal.valueOf(1000000));
        accountRepository.flush();

        assertThatThrownBy(() -> missionService.applyForBankruptcy(member.getEmail()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("아직 파산할 정도로 돈이 없지 않습니다.");
    }

    @Test
    @DisplayName("파산 승인: 2천만원+칭호 지급, 미션 완료, 활동/실력 점수 0 초기화 후 BRONZE 1 강등")
    void applyForBankruptcy_승인시_보상지급과_티어점수_초기화() {
        Member member = createMemberWithMissions();
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member).orElseThrow();
        account.setCash(BigDecimal.valueOf(50000));
        accountRepository.flush();

        Reward reward = missionService.applyForBankruptcy(member.getEmail());

        assertThat(reward.getMoneyAmount()).isEqualTo(20000000L);
        assertThat(reward.getTitleToGrant().getName()).isEqualTo("인생 2회차");
        assertThat(account.getCash()).isEqualByComparingTo(BigDecimal.valueOf(20050000));

        MissionProgress bankruptcy = progressOf(member, BANKRUPTCY_ID);
        assertThat(bankruptcy.getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(bankruptcy.getCurrentValue()).isEqualTo(1000000);

        // 티어 점수 완전 초기화
        assertThat(progressOf(member, ACTIVITY_SCORE_TRACKER_ID).getCurrentValue()).isZero();
        assertThat(progressOf(member, SKILL_SCORE_TRACKER_ID).getCurrentValue()).isZero();

        // 칭호 획득 반영
        List<MemberTitleResponse> titles = missionQueryService.getMyTitles(member.getEmail());
        assertThat(titles).hasSize(1);
        assertThat(titles.get(0).getName()).isEqualTo("인생 2회차");

        // 특성화: 점수 초기화로 BRONZE 1 / 총점 0으로 강등
        UserTierStatusResponse tier = missionQueryService.getTierInfo(member.getEmail());
        assertThat(tier.getCurrentTier()).isEqualTo("BRONZE 1");
        assertThat(tier.getTotalScore()).isZero();
        assertThat(tier.getScoreToNextTier()).isEqualTo(800);
    }

    @Test
    @DisplayName("이미 파산 보상을 받았으면 재신청 시 IllegalStateException이 발생한다")
    void applyForBankruptcy_중복신청시_예외가_발생한다() {
        Member member = createMemberWithMissions();
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member).orElseThrow();
        account.setCash(BigDecimal.valueOf(50000));
        accountRepository.flush();
        missionService.applyForBankruptcy(member.getEmail());

        // 자산 검증이 완료 검증보다 먼저이므로, 다시 빈털터리로 만들어야 중복 분기에 도달한다
        account.setCash(BigDecimal.valueOf(40000));
        accountRepository.flush();

        assertThatThrownBy(() -> missionService.applyForBankruptcy(member.getEmail()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 구조 지원금을 받으셨습니다.");
    }

    // --- 7. RankingService.getTierForMember 경유 계약 (B-2 승격) ---
    // Phase A의 리플렉션 특성화를 계획(Verification Plan 'B-2 후')대로 MissionQueryService 공개 계약으로 승격.
    // getTierForMember는 missionQueryService.getTierInfo(email).getCurrentTier()를 사용하고,
    // 조회 예외는 catch하여 null 폴백한다 — 그 전제(반환 티어 문자열 / 미존재 회원 예외)를 고정한다.

    @Test
    @DisplayName("getTierForMember가 의존하는 공개 계약: getTierInfo는 현재 티어 문자열을 반환한다")
    void getTierForMember_공개계약_정상조회시_현재티어_문자열을_반환한다() {
        Member member = createMemberWithMissions();

        String tier = missionQueryService.getTierInfo(member.getEmail()).getCurrentTier();

        assertThat(tier).isEqualTo("SILVER 1");
    }

    @Test
    @DisplayName("getTierForMember가 의존하는 공개 계약: 미존재 회원 조회는 EntityNotFoundException을 던진다")
    void getTierForMember_공개계약_미존재회원이면_EntityNotFoundException이_발생한다() {
        // 특성화: RankingService.getTierForMember는 이 예외를 catch하여 경고 로그 후 null 폴백한다
        String ghostEmail = "ghost-" + UUID.randomUUID() + "@test.com";

        assertThatThrownBy(() -> missionQueryService.getTierInfo(ghostEmail))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // --- 헬퍼 (슬라이스 자체 소유, 다른 테스트와 공유하지 않음) ---

    private Member createMemberWithMissions() {
        String email = "mission-query-" + UUID.randomUUID() + "@test.com";
        Member member = memberRepository.save(Member.builder()
                .name("query-tester")
                .email(email)
                .password("password")
                .provider(AuthProvider.LOCAL)
                .build());
        accountService.createDefaultAccountForMember(member);
        missionService.initializeMissionsForNewMember(member);
        missionProgressRepository.flush();
        return member;
    }

    private MissionProgress progressOf(Member member, long missionId) {
        Mission mission = missionRepository.findById(missionId).orElseThrow();
        return missionProgressRepository.findByMemberAndMission(member, mission).orElseThrow();
    }
}
