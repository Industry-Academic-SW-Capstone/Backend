package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MissionService 리팩토링 전 특성화(characterization) 테스트.
 * 관찰된 "현재 동작"을 기대값으로 고정한다. 버그로 의심되는 동작도 그대로 단언한다.
 *
 * 전제(관찰 사실):
 * - test 프로파일에는 spring.sql.init 설정이 없어 data.sql이 자동 실행되지 않으므로
 *   테스트에서 직접 ResourceDatabasePopulator로 시드한다(ON CONFLICT upsert라 반복 실행에 안전).
 * - data.sql에는 BUY_COUNT/BUY_AMOUNT/SELL_COUNT/SELL_AMOUNT 조건의 미션이 존재하지 않는다.
 *   따라서 매수/매도 이벤트의 진행도 증가는 TRADE_COUNT(102, 201)와 DAILY_TRADE_COUNT(904)로 관찰한다.
 */
@DisplayName("MissionService 특성화 테스트 (리팩토링 전 현재 동작 고정)")
class MissionServiceIntegrationTest extends IntegrationTestSupport {

    private static final String STOCK_CODE = "CHRTEST"; // stock 테이블에 없는 코드 (checkSpecialAchievement에서 orElse(null) 허용)
    private static final BigDecimal INITIAL_CASH = new BigDecimal("1000000");

    @Autowired
    private MissionService missionService;
    @Autowired
    private MemberRepository memberRepository;
    @Autowired
    private ContestRepository contestRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MissionRepository missionRepository;
    @Autowired
    private MissionProgressRepository missionProgressRepository;
    @Autowired
    private MemberTitleRepository memberTitleRepository;
    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    private TransactionTemplate txTemplate;
    private Long memberId;
    private String memberEmail;
    private Long defaultAccountId;
    private Long contestId;

    @BeforeEach
    void setUp() {
        txTemplate = new TransactionTemplate(transactionManager);

        // data.sql 시드 (title/reward/mission upsert + next_mission 체인 연결)
        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        memberEmail = "mission-char-" + uniqueId + "@test.com";

        txTemplate.executeWithoutResult(status -> {
            Member member = memberRepository.save(Member.builder()
                    .name("특성화" + uniqueId)
                    .email(memberEmail)
                    .provider(AuthProvider.LOCAL)
                    .build());

            Contest contest = contestRepository.save(Contest.builder()
                    .contestName("특성화 대회 " + uniqueId)
                    .startDate(LocalDateTime.now())
                    .seedMoney(1000000L)
                    .commissionRate(new BigDecimal("0.0000"))
                    .isDefault(false)
                    .build());

            Account defaultAccount = accountRepository.save(Account.builder()
                    .member(member)
                    .contest(contest)
                    .accountName("기본 계좌 " + uniqueId)
                    .cash(INITIAL_CASH)
                    .holdAmount(BigDecimal.ZERO)
                    .isDefault(true)
                    .build());

            // 시나리오 1의 대상이자, 나머지 시나리오의 공용 픽스처 경로
            missionService.initializeMissionsForNewMember(member);

            memberId = member.getMemberId();
            defaultAccountId = defaultAccount.getAccountId();
            contestId = contest.getContestId();
        });
    }

    // --- helpers ---

    private MissionProgress progress(long missionId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        Mission mission = missionRepository.findById(missionId).orElseThrow();
        return missionProgressRepository.findByMemberAndMission(member, mission).orElseThrow();
    }

    private void mutateProgress(long missionId, Consumer<MissionProgress> mutator) {
        txTemplate.executeWithoutResult(status -> mutator.accept(progress(missionId)));
    }

    private BigDecimal defaultAccountCash() {
        return accountRepository.findById(defaultAccountId).orElseThrow().getCash();
    }

    private List<String> titleNames() {
        return txTemplate.execute(status -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            return memberTitleRepository.findAllByMember(member).stream()
                    .map(mt -> mt.getTitle().getName())
                    .collect(Collectors.toList());
        });
    }

    private TradeCompletionEvent buyEvent(Long accountId, int quantity, String price) {
        return new TradeCompletionEvent(memberId, accountId, STOCK_CODE,
                OrderMethod.BUY, quantity, new BigDecimal(price));
    }

    private TradeCompletionEvent sellEvent(Long accountId, int quantity, String sellPrice, String buyAveragePrice) {
        return new TradeCompletionEvent(memberId, accountId, STOCK_CODE,
                OrderMethod.SELL, quantity, new BigDecimal(sellPrice),
                null, null, 0, new BigDecimal(buyAveragePrice));
    }

    // --- 시나리오 1 ---

    @Test
    @DisplayName("1. initializeMissionsForNewMember: 트랙 첫 미션만 활성화되고 ACTIVITY_SCORE는 1200으로 시작한다")
    void initializeMissionsForNewMember_트랙첫미션_활성화_및_활동점수_1200() {
        // data.sql 기준 미션 29개(일일 4 + 업적 13 + 중급 6 + 고급 6) 전부 진행도 생성
        Member member = memberRepository.findById(memberId).orElseThrow();
        assertThat(missionProgressRepository.findByMemberWithMissionAndReward(member)).hasSize(29);

        // 트랙별 첫 미션(201/301/401)만 IN_PROGRESS
        for (long id : new long[] {201L, 301L, 401L}) {
            assertThat(progress(id).getStatus()).as("mission %d", id).isEqualTo(MissionStatus.IN_PROGRESS);
            assertThat(progress(id).getCurrentValue()).isZero();
        }
        // 나머지 트랙 미션은 INACTIVE
        for (long id : new long[] {202L, 203L, 204L, 302L, 303L, 304L, 402L, 403L, 404L}) {
            assertThat(progress(id).getStatus()).as("mission %d", id).isEqualTo(MissionStatus.INACTIVE);
        }
        // 일일 미션(101~104)과 업적 미션은 기본 IN_PROGRESS
        for (long id : new long[] {101L, 102L, 103L, 104L, 900L, 901L, 902L, 909L, 998L, 999L}) {
            assertThat(progress(id).getStatus()).as("mission %d", id).isEqualTo(MissionStatus.IN_PROGRESS);
        }
        // 특성화: 활동 점수 트래커(998)만 초기값 1200 (Silver 1 시작), 수익금 트래커(999)는 0
        assertThat(progress(998L).getCurrentValue()).isEqualTo(1200);
        assertThat(progress(999L).getCurrentValue()).isZero();
    }

    // --- 시나리오 2 ---

    @Test
    @DisplayName("2. BUY 이벤트: TRADE_COUNT 계열 진행도가 증가하고 일일 거래 미션(102)이 즉시 완료된다")
    void updateMissionProgress_매수이벤트_TRADE_COUNT_증가() {
        missionService.updateMissionProgress(buyEvent(defaultAccountId, 2, "10000"));

        // 특성화: data.sql에는 BUY_COUNT/BUY_AMOUNT 미션이 없어 TRADE_COUNT 계열만 반응한다
        assertThat(progress(201L).getCurrentValue()).isEqualTo(1);   // SHORT_TERM TRADE_COUNT
        assertThat(progress(904L).getCurrentValue()).isEqualTo(1);   // ACHIEVEMENT DAILY_TRADE_COUNT

        // 일일 거래 미션(102, goal 1) 즉시 완료 -> 보상 150,000원
        assertThat(progress(102L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(progress(102L).getCurrentValue()).isEqualTo(1);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1150000"));

        // 특성화: TOTAL_TRADE_AMOUNT(202)는 INACTIVE 상태라 조회 대상이 아니어서 미집계
        assertThat(progress(202L).getStatus()).isEqualTo(MissionStatus.INACTIVE);
        assertThat(progress(202L).getCurrentValue()).isZero();

        // 미션 1건 완료 -> 활동 점수 +10, 시드 콜로니(901) 완료 카운트 반영
        assertThat(progress(998L).getCurrentValue()).isEqualTo(1210);
        assertThat(progress(901L).getCurrentValue()).isEqualTo(1);
    }

    // --- 시나리오 3 ---

    @Test
    @DisplayName("3. SELL 이벤트: HOLDING_DAYS 리셋 + 수익 실현 업적 + 누적 수익금(999) 반영")
    void updateMissionProgress_매도이벤트_홀딩리셋_및_수익반영() {
        mutateProgress(301L, p -> p.setCurrentValue(1)); // 홀딩 1일차 상태에서 매도

        // 2주를 평단 10,000원에 사서 12,000원에 매도 -> 수익 4,000원
        missionService.updateMissionProgress(sellEvent(defaultAccountId, 2, "12000", "10000"));

        // 특성화: SELL 발생 시 HOLDING_DAYS는 무조건 0으로 리셋 (상태는 IN_PROGRESS 유지)
        assertThat(progress(301L).getCurrentValue()).isZero();
        assertThat(progress(301L).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progress(401L).getCurrentValue()).isZero();

        // SELL도 TRADE_COUNT 공통 조건에 걸린다
        assertThat(progress(201L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(904L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(102L).getStatus()).isEqualTo(MissionStatus.COMPLETED);

        // checkSpecialAchievement: 첫 수익 실현(902) 완료 + 칭호 '달콤한 첫입'
        assertThat(progress(902L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(progress(902L).getCurrentValue()).isEqualTo(1);
        assertThat(titleNames()).containsExactly("달콤한 첫입");

        // updateSkillScore: 999 트래커에 순수익 4,000원 누적
        assertThat(progress(999L).getCurrentValue()).isEqualTo(4000);

        // 현금: 102 보상 150,000 + 902 보상 1,000,000
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("2150000"));

        // 미션 2건 완료 -> 활동 점수 1200 + 20
        assertThat(progress(998L).getCurrentValue()).isEqualTo(1220);
        assertThat(progress(901L).getCurrentValue()).isEqualTo(2);
    }

    // --- 시나리오 4 ---

    @Test
    @DisplayName("4. 보조 계좌(isDefault=false) 이벤트는 미션 집계에서 완전히 제외된다")
    void updateMissionProgress_보조계좌_이벤트_집계제외() {
        Long auxAccountId = txTemplate.execute(status -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            Contest auxContest = contestRepository.save(Contest.builder()
                    .contestName("보조 대회 " + UUID.randomUUID())
                    .startDate(LocalDateTime.now())
                    .seedMoney(1000000L)
                    .commissionRate(new BigDecimal("0.0000"))
                    .isDefault(false)
                    .build());
            Account aux = accountRepository.save(Account.builder()
                    .member(member)
                    .contest(auxContest)
                    .accountName("보조 계좌")
                    .cash(INITIAL_CASH)
                    .holdAmount(BigDecimal.ZERO)
                    .isDefault(false)
                    .build());
            return aux.getAccountId();
        });

        missionService.updateMissionProgress(buyEvent(auxAccountId, 2, "10000"));

        // 아무것도 집계되지 않는다
        assertThat(progress(201L).getCurrentValue()).isZero();
        assertThat(progress(102L).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progress(102L).getCurrentValue()).isZero();
        assertThat(progress(904L).getCurrentValue()).isZero();
        assertThat(progress(998L).getCurrentValue()).isEqualTo(1200);
        assertThat(defaultAccountCash()).isEqualByComparingTo(INITIAL_CASH);
    }

    @Test
    @DisplayName("4-1. AccountId가 null인 이벤트는 경고만 남기고 그대로 집계된다")
    void updateMissionProgress_AccountId_null_이벤트_집계진행() {
        // 특성화: 현재 동작, 버그 의심 — accountId가 null이면 기본 계좌 검증을 건너뛰고 집계를 진행한다
        missionService.updateMissionProgress(buyEvent(null, 1, "10000"));

        assertThat(progress(201L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(102L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1150000"));
    }

    // --- 시나리오 5 ---

    @Test
    @DisplayName("5. 목표 도달 시 완료 -> 보상 지급 -> 다음 미션 활성화 체인이 동작한다")
    void checkMissionCompletion_완료시_보상지급_및_다음미션_활성화() {
        mutateProgress(201L, p -> p.setCurrentValue(9)); // goal 10 직전

        missionService.updateMissionProgress(buyEvent(defaultAccountId, 1, "10000"));

        // 201 완료
        assertThat(progress(201L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(progress(201L).getCurrentValue()).isEqualTo(10);

        // 다음 미션 202가 INACTIVE -> IN_PROGRESS로 활성화
        // 특성화: 활성화는 같은 이벤트 처리 루프 이후이므로 이번 이벤트는 202에 반영되지 않는다(currentValue 0)
        assertThat(progress(202L).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progress(202L).getCurrentValue()).isZero();

        // 현금: 201 보상 300,000 + 일일 거래 미션(102) 보상 150,000
        assertThat(progress(102L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1450000"));

        // 완료 2건 -> 활동 점수 +20, 시드 콜로니 카운트 2
        assertThat(progress(998L).getCurrentValue()).isEqualTo(1220);
        assertThat(progress(901L).getCurrentValue()).isEqualTo(2);
    }

    // --- 시나리오 6 ---

    @Test
    @DisplayName("6. ADVANCED 미션 완료 시 트랙이 리셋되고 완료 기록 자체도 초기화된다")
    void activateNextMission_고급미션완료_트랙리셋() {
        // SHORT_TERM 트랙을 마지막 미션(204, DAILY_PROFIT_COUNT goal 3) 직전 상태로 세팅
        mutateProgress(201L, MissionProgress::complete);
        mutateProgress(202L, MissionProgress::complete);
        mutateProgress(203L, MissionProgress::complete);
        mutateProgress(204L, p -> {
            p.activate();
            p.setCurrentValue(2);
        });
        // 첫 수익 업적(902)은 미리 완료 처리하여 이번 시나리오에서 제외
        mutateProgress(902L, p -> {
            p.setCurrentValue(1);
            p.complete();
        });

        // 익절 매도 -> 204 진행도 3 도달
        missionService.updateMissionProgress(sellEvent(defaultAccountId, 1, "11000", "10000"));

        // 특성화: 현재 동작, 버그 의심 — 204 완료 직후 resetMissionTrack이 트랙 전체(자기 자신 포함)를
        // reset+deactivate 하므로 204의 COMPLETED 기록과 진행도가 소실된다(보상 현금은 이미 지급됨)
        assertThat(progress(204L).getStatus()).isEqualTo(MissionStatus.INACTIVE);
        assertThat(progress(204L).getCurrentValue()).isZero();

        // 트랙 첫 미션(201)만 재활성화, 나머지는 INACTIVE + 0
        assertThat(progress(201L).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);
        assertThat(progress(201L).getCurrentValue()).isZero();
        assertThat(progress(202L).getStatus()).isEqualTo(MissionStatus.INACTIVE);
        assertThat(progress(202L).getCurrentValue()).isZero();
        assertThat(progress(203L).getStatus()).isEqualTo(MissionStatus.INACTIVE);
        assertThat(progress(203L).getCurrentValue()).isZero();

        // 현금: 204 보상 2,000,000 + 일일 거래(102) 보상 150,000
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("3150000"));

        // 매도 수익 1,000원은 999 트래커에 누적
        assertThat(progress(999L).getCurrentValue()).isEqualTo(1000);
    }

    // --- 시나리오 7 ---

    @Test
    @DisplayName("7. 수익 매도 일회성 업적(달콤한 첫입)은 두 번째 수익에도 중복 지급되지 않는다")
    void checkSpecialAchievement_첫수익업적_일회성() {
        missionService.updateMissionProgress(sellEvent(defaultAccountId, 1, "11000", "10000"));
        missionService.updateMissionProgress(sellEvent(defaultAccountId, 1, "11000", "10000"));

        assertThat(progress(902L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(progress(902L).getCurrentValue()).isEqualTo(1);
        // 칭호는 정확히 1회만 지급
        assertThat(titleNames()).containsExactly("달콤한 첫입");
        // 현금: 첫 이벤트에서만 102 보상 150,000 + 902 보상 1,000,000 (두 번째 이벤트는 보상 없음)
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("2150000"));

        // 특성화: 업적은 일회성이지만 누적 수익금(999)은 매 매도마다 계속 누적된다 (1,000 x 2)
        assertThat(progress(999L).getCurrentValue()).isEqualTo(2000);
        assertThat(progress(904L).getCurrentValue()).isEqualTo(2);
    }

    // --- 시나리오 8 ---

    @Test
    @DisplayName("8. processRankerAchievement: Top10 진입 시 909 완료 + 랭커 칭호, 재호출 시 중복 지급 없음")
    void processRankerAchievement_랭커업적_완료() {
        // 존재하지 않는 회원 ID는 조용히 건너뛴다
        missionService.processRankerAchievement(List.of(memberId, 999_999_999L));

        assertThat(progress(909L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        // 특성화: 목표치 10을 하드코딩으로 setCurrentValue(10) 처리
        assertThat(progress(909L).getCurrentValue()).isEqualTo(10);
        assertThat(titleNames()).containsExactly("랭커");
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("16000000"));
        assertThat(progress(998L).getCurrentValue()).isEqualTo(1210);

        // 재호출: 이미 완료된 회원은 보상/칭호 중복 지급 없음
        missionService.processRankerAchievement(List.of(memberId));
        assertThat(titleNames()).containsExactly("랭커");
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("16000000"));
    }

    // --- 시나리오 9 ---

    @Test
    @DisplayName("9-1. handleReportView: 리포트 3회 열람 시 일일 미션(103)이 완료된다")
    void handleReportView_일일미션_진행_및_완료() {
        missionService.handleReportView(memberEmail);
        assertThat(progress(103L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(103L).getStatus()).isEqualTo(MissionStatus.IN_PROGRESS);

        missionService.handleReportView(memberEmail);
        missionService.handleReportView(memberEmail);
        assertThat(progress(103L).getCurrentValue()).isEqualTo(3);
        assertThat(progress(103L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1150000"));
    }

    @Test
    @DisplayName("9-2. handlePortfolioAnalysis: 1회 분석으로 일일 미션(104) 완료, 재호출은 무시된다")
    void handlePortfolioAnalysis_일일미션_완료_및_중복무시() {
        missionService.handlePortfolioAnalysis(memberEmail);
        assertThat(progress(104L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(104L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1150000"));

        // 이미 완료된 미션은 진행/보상 없이 무시
        missionService.handlePortfolioAnalysis(memberEmail);
        assertThat(progress(104L).getCurrentValue()).isEqualTo(1);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1150000"));
    }

    // --- 시나리오 10 ---

    @Test
    @DisplayName("10. 동기 리스너 경유: 트랜잭션 안에서 발행한 TradeCompletionEvent가 커밋 후 진행도에 반영된다")
    void 동기리스너_경유_이벤트발행_커밋후_반영() {
        // MissionEventListener.handleTradeCompletionEvent는 @EventListener(동기)라
        // publishEvent 시점에 발행자 트랜잭션에 참여하여 즉시 실행된다
        txTemplate.executeWithoutResult(status ->
                eventPublisher.publishEvent(buyEvent(defaultAccountId, 2, "10000")));

        assertThat(progress(201L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(904L).getCurrentValue()).isEqualTo(1);
        assertThat(progress(102L).getStatus()).isEqualTo(MissionStatus.COMPLETED);
        assertThat(defaultAccountCash()).isEqualByComparingTo(new BigDecimal("1150000"));
    }
}
