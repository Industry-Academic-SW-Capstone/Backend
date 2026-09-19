package grit.stockIt.domain.settlement;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderHoldStatus;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.order.service.OrderHoldService;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// 체결(tx1)이 커밋된 뒤 정산(tx2)이 돌기까지 종목 락이 풀려 있어, 그 사이 취소·만료가
// 홀딩을 푼다. 정산이 그때 뺄 것을 잃으면 복구 배치가 같은 자리에서 영원히 실패한다.
@DisplayName("홀딩 해제와 정산의 경합 (통합 테스트)")
class SettlementAfterHoldReleaseTest extends IntegrationTestSupport {

    @Autowired private ExecutionSettlementService executionSettlementService;
    @Autowired private OrderHoldService orderHoldService;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderHoldRepository orderHoldRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("부분체결 주문의 홀딩을 풀어도 미정산 체결분은 남는다")
    void releaseBuyHold_keepsUnsettledFillAmount() {
        Fixture fx = fixture(10, 4);

        releaseHold(fx.orderId());

        OrderHold hold = orderHoldRepository.findById(fx.orderId()).orElseThrow();
        assertThat(hold.getHoldAmount()).isEqualByComparingTo(new BigDecimal("400"));
        assertThat(hold.getStatus()).isEqualTo(OrderHoldStatus.ACTIVE);

        Account account = accountRepository.findById(fx.accountId()).orElseThrow();
        assertThat(account.getHoldAmount()).isEqualByComparingTo(new BigDecimal("400"));
    }

    @Test
    @DisplayName("체결이 없으면 홀딩이 전액 풀린다")
    void releaseBuyHold_withoutFills_releasesEverything() {
        Fixture fx = fixture(10);

        releaseHold(fx.orderId());

        OrderHold hold = orderHoldRepository.findById(fx.orderId()).orElseThrow();
        assertThat(hold.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(hold.getStatus()).isEqualTo(OrderHoldStatus.RELEASED);
        assertThat(accountRepository.findById(fx.accountId()).orElseThrow().getHoldAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("홀딩 해제가 먼저 끼어들어도 정산이 성공하고 현금이 정확히 빠진다")
    void settle_afterHoldRelease_succeeds() {
        Fixture fx = fixture(10, 4);
        releaseHold(fx.orderId());

        executionSettlementService.settle(fx.executionIds().get(0));

        assertThat(settlementRepository.existsByExecutionId(fx.executionIds().get(0))).isTrue();
        Account account = accountRepository.findById(fx.accountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999600"));
        assertThat(account.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("먼저 난 체결이 미정산인 채로 주문이 다 차도, 그 체결은 나중에 정산된다")
    void settle_lateFirstFill_afterOrderFullyFilled() {
        Fixture fx = fixture(10, 4, 6);

        executionSettlementService.settle(fx.executionIds().get(1));

        // 주문이 다 찼지만 첫 체결이 미정산이라, 그 몫은 홀딩에 남아 있어야 한다.
        assertThat(orderHoldRepository.findById(fx.orderId()).orElseThrow().getHoldAmount())
                .isEqualByComparingTo(new BigDecimal("400"));

        executionSettlementService.settle(fx.executionIds().get(0));

        assertThat(settlementRepository.existsByExecutionId(fx.executionIds().get(0))).isTrue();
        Account account = accountRepository.findById(fx.accountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
        assertThat(account.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("홀딩이 체결 금액보다 커도 전량 체결 정산 후 남은 홀딩이 풀린다")
    void settle_fullFill_releasesHoldOverhang() {
        // 시장가 매수는 상한가로 홀딩을 잡아 체결 금액보다 크다. 정산이 그 차액까지 풀어야
        // 계좌에 묶인 돈이 남지 않는다.
        Fixture fx = fixture(10, new BigDecimal("1200"), 10);

        executionSettlementService.settle(fx.executionIds().get(0));

        OrderHold hold = orderHoldRepository.findById(fx.orderId()).orElseThrow();
        assertThat(hold.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(hold.getStatus()).isEqualTo(OrderHoldStatus.RELEASED);

        Account account = accountRepository.findById(fx.accountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(new BigDecimal("999000"));
        assertThat(account.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private void releaseHold(Long orderId) {
        inTransaction(() -> {
            orderHoldService.releaseBuyHold(orderRepository.findById(orderId).orElseThrow());
            return null;
        });
    }

    private record Fixture(Long accountId, Long orderId, List<Long> executionIds) {
    }

    // 주문 100원 × quantity, 현금 100만원. fills 는 체결 수량을 순서대로 낸다.
    private Fixture fixture(int quantity, int... fills) {
        return fixture(quantity, new BigDecimal("1000"), fills);
    }

    private Fixture fixture(int quantity, BigDecimal holdAmount, int... fills) {
        return inTransaction(() -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Member member = memberRepository.save(Member.builder()
                    .name("경합 " + suffix)
                    .email("hold-race-" + suffix + "@test.com")
                    .provider(AuthProvider.LOCAL)
                    .build());
            Contest contest = contestRepository.save(Contest.builder()
                    .contestName("경합 대회 " + suffix)
                    .startDate(LocalDateTime.now())
                    .seedMoney(10_000_000L)
                    .commissionRate(new BigDecimal("0.0000"))
                    .isDefault(false)
                    .build());
            Account account = accountRepository.save(Account.builder()
                    .member(member).contest(contest)
                    .accountName("경합 계좌 " + suffix)
                    .cash(new BigDecimal("1000000"))
                    .holdAmount(holdAmount)
                    .isDefault(false)
                    .build());
            Stock stock = stockRepository.save(Stock.builder()
                    .code("T" + suffix).name("경합종목 " + suffix).marketType("KOSPI").build());

            Order order = orderRepository.save(
                    Order.createLimitOrder(account, stock, new BigDecimal("100"), quantity, OrderMethod.BUY));
            orderHoldRepository.save(OrderHold.create(order, account, holdAmount));

            List<Long> executionIds = new ArrayList<>();
            for (int fill : fills) {
                order.applyFill(fill);
                executionIds.add(executionRepository.save(
                        Execution.of(order, new BigDecimal("100"), fill)).getExecutionId());
            }
            orderRepository.save(order);

            return new Fixture(account.getAccountId(), order.getOrderId(), executionIds);
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            T result = work.get();
            transactionManager.commit(status);
            return result;
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
            throw e;
        }
    }
}
