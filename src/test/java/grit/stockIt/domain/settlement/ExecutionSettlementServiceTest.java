package grit.stockIt.domain.settlement;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.domain.settlement.scheduler.UnsettledExecutionScheduler;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("체결 정산과 미정산 복구 (통합 테스트)")
class ExecutionSettlementServiceTest extends IntegrationTestSupport {

    private static final LocalDateTime FUTURE = LocalDateTime.now().plusYears(1);

    @Autowired private ExecutionSettlementService executionSettlementService;
    @Autowired private UnsettledExecutionScheduler unsettledExecutionScheduler;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderHoldRepository orderHoldRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountStockRepository accountStockRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("매수 체결을 정산하면 현금이 빠지고 보유종목이 늘며 홀딩이 풀린다")
    void settle_buyExecution_appliesCashAndHolding() {
        Fixture fx = buyFixture(10);

        executionSettlementService.settle(fx.execution().getExecutionId());

        Account account = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
        assertThat(account.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        AccountStock holding = accountStockRepository
                .findByAccountAndStock(account, fx.stock()).orElseThrow();
        assertThat(holding.getQuantity()).isEqualTo(10);

        assertThat(settlementRepository.existsByExecutionId(fx.execution().getExecutionId())).isTrue();
    }

    @Test
    @DisplayName("같은 체결을 두 번 정산해도 현금이 한 번만 빠진다")
    void settle_twice_appliesOnce() {
        Fixture fx = buyFixture(10);

        executionSettlementService.settle(fx.execution().getExecutionId());
        executionSettlementService.settle(fx.execution().getExecutionId());

        Account account = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
        assertThat(settlementRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("정산된 체결은 미정산 목록에서 빠진다")
    void unsettledQuery_excludesSettled() {
        Fixture first = buyFixture(10);
        Fixture second = buyFixture(10);

        assertThat(executionSettlementService.findUnsettledExecutionIds(FUTURE, 100))
                .contains(first.execution().getExecutionId(), second.execution().getExecutionId());

        executionSettlementService.settle(first.execution().getExecutionId());

        assertThat(executionSettlementService.findUnsettledExecutionIds(FUTURE, 100))
                .doesNotContain(first.execution().getExecutionId())
                .contains(second.execution().getExecutionId());
    }

    @Test
    @DisplayName("복구 배치가 미정산 체결을 마저 정산한다")
    void recoveryScheduler_settlesLeftovers() {
        Fixture fx = buyFixture(10);
        // created_at 은 감사 컬럼이라 UPDATE 로 과거로 못 돌린다(updatable=false).
        // 대신 유예 시간을 없애 방금 만든 체결도 대상이 되게 한다.
        ReflectionTestUtils.setField(unsettledExecutionScheduler, "gracePeriod", Duration.ZERO);

        unsettledExecutionScheduler.recoverUnsettledExecutions();

        assertThat(settlementRepository.existsByExecutionId(fx.execution().getExecutionId())).isTrue();
        Account account = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
    }

    private record Fixture(Account account, Stock stock, Order order, Execution execution) {
    }

    private Fixture buyFixture(int quantity) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            Fixture fixture = createBuyFixture(quantity);
            transactionManager.commit(status);
            return fixture;
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    // OrderHold 가 @MapsId 로 주문 식별자를 공유하므로 주문이 영속 상태여야 한다.
    // 저장소를 트랜잭션 밖에서 하나씩 부르면 주문이 준영속이 되어 persist 가 거부된다.
    private Fixture createBuyFixture(int quantity) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("정산 " + suffix)
                .email("settle-" + suffix + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("정산 대회 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Account account = accountRepository.save(Account.builder()
                .member(member).contest(contest)
                .accountName("정산 계좌 " + suffix)
                .cash(new BigDecimal("1000000"))
                .holdAmount(new BigDecimal("1000"))
                .isDefault(false)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("T" + suffix).name("정산종목 " + suffix).marketType("KOSPI").build());

        Order order = Order.createLimitOrder(account, stock, new BigDecimal("100"), quantity, OrderMethod.BUY);
        order.applyFill(quantity);
        order = orderRepository.save(order);
        orderHoldRepository.save(OrderHold.create(order, account, new BigDecimal("1000")));

        Execution execution = executionRepository.save(
                Execution.of(order, new BigDecimal("100"), quantity));
        return new Fixture(account, stock, order, execution);
    }
}
