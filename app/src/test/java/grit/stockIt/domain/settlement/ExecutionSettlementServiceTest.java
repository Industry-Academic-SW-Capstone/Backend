package grit.stockIt.domain.settlement;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import grit.stockIt.global.support.TestExecutionIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("체결 통보로 정산 (통합 테스트)")
class ExecutionSettlementServiceTest extends IntegrationTestSupport {

    @Autowired private ExecutionSettlementService executionSettlementService;
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
    @DisplayName("매수 체결 통보를 정산하면 체결 내역이 남고, 주문이 체결 완료되며, 현금이 빠지고 보유종목이 늘고 홀딩이 풀린다")
    void settle_buyExecution_appliesCashAndHolding() {
        Fixture fx = buyFixture(10);
        ExecutionReport report = fill(fx, 10, 10, 0);

        executionSettlementService.settle(report);

        Account account = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
        assertThat(account.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(accountStockRepository.findByAccountAndStock(account, fx.stock()).orElseThrow().getQuantity())
                .isEqualTo(10);
        assertThat(settlementRepository.existsByExecutionId(report.executionId())).isTrue();
        assertThat(executionRepository.findById(report.executionId())).isPresent();

        Order order = orderRepository.findById(fx.order().getOrderId()).orElseThrow();
        assertThat(order.getFilledQuantity()).isEqualTo(10);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    @DisplayName("남은 수량이 있는 체결 통보는 주문을 일부 체결로 두고 남은 홀딩을 풀지 않는다")
    void settle_partialFill_keepsRemainingHold() {
        Fixture fx = buyFixture(10);

        executionSettlementService.settle(fill(fx, 4, 4, 6));

        Order order = orderRepository.findById(fx.order().getOrderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(order.getFilledQuantity()).isEqualTo(4);
        assertThat(accountRepository.findById(fx.account().getAccountId()).orElseThrow().getHoldAmount())
                .isEqualByComparingTo(new BigDecimal("600"));
    }

    @Test
    @DisplayName("같은 체결 통보를 두 번 정산해도 현금이 한 번만 빠진다")
    void settle_twice_appliesOnce() {
        Fixture fx = buyFixture(10);
        ExecutionReport report = fill(fx, 10, 10, 0);

        executionSettlementService.settle(report);
        executionSettlementService.settle(report);

        Account account = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
        assertThat(settlementRepository.existsByExecutionId(report.executionId())).isTrue();
    }

    private record Fixture(Account account, Stock stock, Order order) {
    }

    private static ExecutionReport fill(Fixture fx, int quantity, int cumQuantity, int leavesQuantity) {
        return ExecutionReport.fill(TestExecutionIds.next(), fx.order().getOrderId(), fx.account().getAccountId(),
                fx.stock().getCode(), OrderMethod.BUY, new BigDecimal("100"), quantity, cumQuantity, leavesQuantity,
                System.currentTimeMillis());
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

        Order order = orderRepository.save(
                Order.createLimitOrder(account, stock, new BigDecimal("100"), quantity, OrderMethod.BUY));
        orderHoldRepository.save(OrderHold.create(order, account, new BigDecimal("1000")));
        return new Fixture(account, stock, order);
    }
}
