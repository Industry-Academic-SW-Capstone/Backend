package grit.stockIt.domain.mission.service;

import static org.assertj.core.api.Assertions.assertThat;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.account.service.AccountService;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

/**
 * [특성화 테스트] MissionScheduler 진입 경로(자정/자정+1분 스케줄)의 현재 동작을
 * 서비스 메서드 직접 호출로 고정한다. (스케줄링 자체는 테스트 프로파일에서 비활성)
 *
 * 리팩토링 전 안전망이므로 "이상해 보이는" 동작도 관찰된 그대로 단언한다.
 */
@Transactional
@Sql("/data.sql")
class MissionSchedulerFlowIntegrationTest extends IntegrationTestSupport {

    private static final long DAILY_ATTENDANCE_ID = 101L;
    private static final long DAILY_REPORT_VIEW_ID = 103L;
    private static final long SHORT_TERM_FIRST_ID = 201L;
    private static final long SWING_HOLDING_2D_ID = 301L;
    private static final long SWING_PROFIT_5PCT_ID = 302L;
    private static final long SWING_HOLDING_7D_ID = 303L;
    private static final long LONG_TERM_HOLDING_7D_ID = 401L;
    private static final long LONG_TERM_HOLDING_16D_ID = 403L;
    private static final long STREAK_TRACKER_ID = 900L;
    private static final long STREAK_7D_ID = 906L;
    private static final long STREAK_15D_ID = 907L;
    private static final long STREAK_30D_ID = 908L;
    private static final long KITING_ACHIEVEMENT_ID = 904L;
    private static final long ACTIVITY_SCORE_TRACKER_ID = 998L;

    @Autowired
    private MissionService missionService;

    @Autowired
    private MissionBatchService missionBatchService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountStockRepository accountStockRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private MissionRepository missionRepository;

    @Autowired
    private MissionProgressRepository missionProgressRepository;

    // --- 1. checkAndResetAttendanceStreaks ---

    @Test
    @DisplayName("출석 미체크 회원의 연속 출석 기록만 0으로 초기화되고, 출석 완료 회원의 기록은 보존된다")
    void checkAndResetAttendanceStreaks_출석미체크_회원만_스트릭이_리셋된다() {
        // given: 출석하지 않은 회원 (일일 출석 미션 IN_PROGRESS, 스트릭 5)
        Member absentee = createMemberWithMissions();
        progressOf(absentee, STREAK_TRACKER_ID).setCurrentValue(5);
        progressOf(absentee, STREAK_7D_ID).setCurrentValue(5);
        progressOf(absentee, STREAK_15D_ID).setCurrentValue(5);
        progressOf(absentee, STREAK_30D_ID).setCurrentValue(5);

        // given: 출석을 완료한 회원 (연쇄 갱신으로 모든 스트릭 진행도 1)
        Member attendee = createMemberWithMissions();
        missionService.claimDailyAttendance(attendee.getEmail());
        missionProgressRepository.flush();

        // when
        missionBatchService.checkAndResetAttendanceStreaks();

        // then: 미출석 회원의 LOGIN_STREAK 계열(트래커 900 포함)은 전부 0으로 벌크 리셋
        assertThat(progressOf(absentee, STREAK_TRACKER_ID).getCurrentValue()).isZero();
        assertThat(progressOf(absentee, STREAK_7D_ID).getCurrentValue()).isZero();
        assertThat(progressOf(absentee, STREAK_15D_ID).getCurrentValue()).isZero();
        assertThat(progressOf(absentee, STREAK_30D_ID).getCurrentValue()).isZero();
        // 특성화: 리셋은 currentValue만 0으로 만들고 상태(IN_PROGRESS)는 그대로 유지
        assertThat(progressOf(absentee, STREAK_TRACKER_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        // 일일 출석 미션 자체는 건드리지 않음
        assertThat(progressOf(absentee, DAILY_ATTENDANCE_ID).getCurrentValue()).isZero();
        assertThat(progressOf(absentee, DAILY_ATTENDANCE_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);

        // then: 출석 완료 회원의 스트릭은 보존
        assertThat(progressOf(attendee, STREAK_TRACKER_ID).getCurrentValue()).isEqualTo(1);
        assertThat(progressOf(attendee, STREAK_7D_ID).getCurrentValue()).isEqualTo(1);
    }

    // --- 2. resetDailyMissions ---

    @Test
    @DisplayName("일일 미션은 완료 여부와 무관하게 진행도 0/IN_PROGRESS로 초기화되고, 카이팅 업적도 함께 리셋된다")
    void resetDailyMissions_일일미션_진행도와_상태를_초기화한다() {
        // given: 출석 완료(101 COMPLETED) + 리포트 미션 일부 진행 + 트랙 미션 진행 + 카이팅 진행
        Member member = createMemberWithMissions();
        missionService.claimDailyAttendance(member.getEmail());
        progressOf(member, DAILY_REPORT_VIEW_ID).setCurrentValue(2);
        progressOf(member, SHORT_TERM_FIRST_ID).setCurrentValue(3);
        progressOf(member, KITING_ACHIEVEMENT_ID).setCurrentValue(10);
        missionProgressRepository.flush();

        // when
        missionBatchService.resetDailyMissions();

        // then: DAILY 트랙 전체가 0/IN_PROGRESS로 리셋 (완료된 출석 미션 포함)
        assertThat(progressOf(member, DAILY_ATTENDANCE_ID).getCurrentValue()).isZero();
        assertThat(progressOf(member, DAILY_ATTENDANCE_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progressOf(member, DAILY_REPORT_VIEW_ID).getCurrentValue()).isZero();
        assertThat(progressOf(member, DAILY_REPORT_VIEW_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);

        // 특성화: ACHIEVEMENT 트랙이지만 DAILY_TRADE_COUNT(카이팅 장인)도 진행 중이면 매일 0으로 리셋
        assertThat(progressOf(member, KITING_ACHIEVEMENT_ID).getCurrentValue()).isZero();
        assertThat(progressOf(member, KITING_ACHIEVEMENT_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);

        // 트랙(SHORT_TERM) 미션 진행도는 영향 없음
        assertThat(progressOf(member, SHORT_TERM_FIRST_ID).getCurrentValue()).isEqualTo(3);

        // 특성화: 연속 출석 트래커(900)는 resetDailyMissions가 건드리지 않음 (출석 연쇄로 1 유지)
        assertThat(progressOf(member, STREAK_TRACKER_ID).getCurrentValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 완료된 카이팅 업적(904)은 일일 리셋 대상에서 제외된다")
    void resetDailyMissions_완료된_카이팅업적은_리셋되지_않는다() {
        // given
        Member member = createMemberWithMissions();
        MissionProgress kiting = progressOf(member, KITING_ACHIEVEMENT_ID);
        kiting.setCurrentValue(50);
        kiting.complete();
        missionProgressRepository.flush();

        // when
        missionBatchService.resetDailyMissions();

        // then: IN_PROGRESS 상태의 DAILY_TRADE_COUNT만 리셋되므로 완료본은 보존
        assertThat(progressOf(member, KITING_ACHIEVEMENT_ID).getCurrentValue()).isEqualTo(50);
        assertThat(progressOf(member, KITING_ACHIEVEMENT_ID).getStatus()).isEqualTo(MissionStatus.COMPLETED);
    }

    // --- 3. processDailyHoldingUpdate ---

    @Test
    @DisplayName("보유 주식이 있는 회원의 진행 중 HOLDING_DAYS 미션만 +1 되고, 무보유 회원과 INACTIVE 미션은 변하지 않는다")
    void processDailyHoldingUpdate_보유주식이_있으면_진행중_홀딩미션만_1증가한다() {
        // given
        Member holder = createMemberWithMissions();
        giveStock(holder);
        Member noStockMember = createMemberWithMissions();
        missionProgressRepository.flush();

        // when
        missionBatchService.processDailyHoldingUpdate();

        // then: 진행 중(IN_PROGRESS)인 홀딩 미션(301, 401)만 +1
        assertThat(progressOf(holder, SWING_HOLDING_2D_ID).getCurrentValue()).isEqualTo(1);
        assertThat(progressOf(holder, SWING_HOLDING_2D_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progressOf(holder, LONG_TERM_HOLDING_7D_ID).getCurrentValue()).isEqualTo(1);

        // INACTIVE 홀딩 미션(303, 403)은 대상 아님
        assertThat(progressOf(holder, SWING_HOLDING_7D_ID).getCurrentValue()).isZero();
        assertThat(progressOf(holder, SWING_HOLDING_7D_ID).getStatus()).isEqualTo(MissionStatus.INACTIVE);
        assertThat(progressOf(holder, LONG_TERM_HOLDING_16D_ID).getCurrentValue()).isZero();

        // 주식이 없는 회원은 변화 없음
        assertThat(progressOf(noStockMember, SWING_HOLDING_2D_ID).getCurrentValue()).isZero();
        assertThat(progressOf(noStockMember, LONG_TERM_HOLDING_7D_ID).getCurrentValue()).isZero();
    }

    @Test
    @DisplayName("홀딩 일수가 목표에 도달하면 완료 전이·보상 지급·다음 미션 활성화·활동 점수 +10이 일어난다")
    void processDailyHoldingUpdate_임계도달시_완료전이와_보상지급_다음미션_활성화() {
        // given: 2일 홀딩 미션(301, 목표 2)이 있는 회원
        Member holder = createMemberWithMissions();
        giveStock(holder);
        Account account = accountRepository.findByMemberAndIsDefaultTrue(holder).orElseThrow();
        BigDecimal cashBefore = account.getCash();
        missionProgressRepository.flush();

        // when: 이틀치 스케줄 실행
        missionBatchService.processDailyHoldingUpdate();
        missionBatchService.processDailyHoldingUpdate();

        // then: 301(목표 2)은 완료 전이
        MissionProgress swingHolding = progressOf(holder, SWING_HOLDING_2D_ID);
        assertThat(swingHolding.getCurrentValue()).isEqualTo(2);
        assertThat(swingHolding.getStatus()).isEqualTo(MissionStatus.COMPLETED);

        // 401(목표 7)은 계속 진행 중
        assertThat(progressOf(holder, LONG_TERM_HOLDING_7D_ID).getCurrentValue()).isEqualTo(2);
        assertThat(progressOf(holder, LONG_TERM_HOLDING_7D_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);

        // 다음 미션(302)이 INACTIVE -> IN_PROGRESS로 활성화
        assertThat(progressOf(holder, SWING_PROFIT_5PCT_ID).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progressOf(holder, SWING_PROFIT_5PCT_ID).getCurrentValue()).isZero();

        // 보상(reward 21: 750,000원)이 기본 계좌에 지급
        assertThat(account.getCash()).isEqualByComparingTo(cashBefore.add(BigDecimal.valueOf(750000)));

        // 특성화: 미션 1건 완료 시 활동 점수 트래커(998, 초기값 1200) +10
        assertThat(progressOf(holder, ACTIVITY_SCORE_TRACKER_ID).getCurrentValue()).isEqualTo(1210);
    }

    // --- 헬퍼 (슬라이스 자체 소유, 다른 테스트와 공유하지 않음) ---

    private Member createMemberWithMissions() {
        String email = "scheduler-" + UUID.randomUUID() + "@test.com";
        Member member = memberRepository.save(Member.builder()
                .name("scheduler-tester")
                .email(email)
                .password("password")
                .provider(AuthProvider.LOCAL)
                .build());
        accountService.createDefaultAccountForMember(member);
        missionService.initializeMissionsForNewMember(member);
        missionProgressRepository.flush();
        return member;
    }

    private void giveStock(Member member) {
        Account account = accountRepository.findByMemberAndIsDefaultTrue(member).orElseThrow();
        Stock stock = stockRepository.save(Stock.builder()
                .code("TST" + UUID.randomUUID().toString().substring(0, 8))
                .name("특성화테스트종목")
                .marketType("KOSPI")
                .build());
        accountStockRepository.save(AccountStock.create(account, stock, 10, BigDecimal.valueOf(1000)));
    }

    private MissionProgress progressOf(Member member, long missionId) {
        Mission mission = missionRepository.findById(missionId).orElseThrow();
        return missionProgressRepository.findByMemberAndMission(member, mission).orElseThrow();
    }
}
