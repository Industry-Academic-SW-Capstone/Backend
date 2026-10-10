package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkRepository;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.settlement.queue.SettlementRequest;
import grit.stockIt.domain.settlement.queue.SettlementTopics;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.support.KafkaIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("체결 큐 → 체결 워커 → 정산 큐 → 정산 워커, 취소 (Kafka 통합)")
class MatchingQueueFlowIntegrationTest extends KafkaIntegrationTestSupport {

    private static final BigDecimal PRICE = new BigDecimal("200");
    private static final BigDecimal INITIAL_CASH = new BigDecimal("1000000");

    @Autowired private FillCommandPublisher fillCommandPublisher;
    @Autowired private OrderCancelCommandPublisher orderCancelCommandPublisher;
    @Autowired private ConsumerWatermarkRepository consumerWatermarkRepository;
    @Autowired private KafkaTemplate<String, SettlementRequest> settlementTemplate;
    @Autowired private SettlementRepository settlementRepository;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderHoldRepository orderHoldRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountStockRepository accountStockRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @Test
    @DisplayName("입구에 넣은 체결 명령이 체결되고, 정산 워커가 계좌에 반영한다")
    void fillCommand_filledThenSettled() {
        BuyOrder buy = transactionTemplate.execute(status -> createBuyOrder());

        fillCommandPublisher.publish(buy.stockCode(), new LimitOrderFillEvent(
                UUID.randomUUID().toString(), OrderMethod.SELL, PRICE, 1, System.currentTimeMillis()));

        await().atMost(WAIT).until(() -> executionOf(buy.orderId()) != null
                && settlementRepository.existsByExecutionId(executionOf(buy.orderId()).getExecutionId()));

        Order order = orderRepository.findById(buy.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
        Account account = accountRepository.findById(buy.accountId()).orElseThrow();
        assertThat(account.getCash()).isEqualByComparingTo(INITIAL_CASH.subtract(PRICE));
        assertThat(accountStockRepository.findByAccountAndStock(account, stockRepository.findById(buy.stockCode()).orElseThrow()))
                .hasValueSatisfying(holding -> assertThat(holding.getQuantity()).isEqualTo(1));
        // 지표는 정산 커밋 직후에 기록된다. 정산 행이 보인 순간에는 아직일 수 있다.
        await().atMost(WAIT).until(() -> meterRegistry.get("settlement.e2e").timer().count() > 0);
    }

    @Test
    @DisplayName("취소가 뒤따르는 체결보다 먼저 들어오면 같은 줄에서 먼저 처리되어, 주문은 체결되지 않고 홀딩이 풀린다")
    void cancelThenFill_sameStock_cancelledNotFilled() {
        BuyOrder buy = transactionTemplate.execute(status -> createBuyOrder());

        assertThat(orderCancelCommandPublisher.publish(buy.stockCode(), buy.orderId())).isTrue();
        FillDispatchResult.Queued fill = (FillDispatchResult.Queued) fillCommandPublisher.publish(buy.stockCode(),
                new LimitOrderFillEvent(UUID.randomUUID().toString(), OrderMethod.SELL, PRICE, 1, System.currentTimeMillis()));

        await().atMost(WAIT).until(() -> lastOffsetOf(fill.partition()) >= fill.offset());

        assertThat(orderRepository.findById(buy.orderId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(executionOf(buy.orderId())).isNull();
        assertThat(accountRepository.findById(buy.accountId()).orElseThrow().getHoldAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("처리할 수 없는 정산 요청은 재시도하지 않고 DLT 로 보낸다")
    void invalidSettlementRequest_toDlt() throws Exception {
        long marker = System.nanoTime();
        settlementTemplate.send(SettlementTopics.REQUESTS, "1", new SettlementRequest(null, 1L, marker)).get();

        assertThat(findDeadLetter(SettlementTopics.REQUESTS, value -> value.contains(String.valueOf(marker)))).isTrue();
    }

    private Execution executionOf(Long orderId) {
        List<Execution> executions = executionRepository.findByOrderIdInWithOrder(List.of(orderId));
        return executions.isEmpty() ? null : executions.get(0);
    }

    private long lastOffsetOf(int partition) {
        return consumerWatermarkRepository.findAll().stream()
                .filter(row -> row.getId().getTopic().equals(MatchingTopics.COMMANDS)
                        && row.getId().getPartitionNo() == partition)
                .mapToLong(ConsumerWatermark::getLastOffset)
                .findFirst()
                .orElse(-1L);
    }

    private record BuyOrder(Long orderId, Long accountId, String stockCode) {
    }

    // OrderHold 가 @MapsId 로 주문 식별자를 공유하므로 주문이 영속 상태인 한 트랜잭션 안에서 만든다.
    private BuyOrder createBuyOrder() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("큐 " + suffix)
                .email("queue-" + suffix + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("큐 대회 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Account account = accountRepository.save(Account.builder()
                .member(member).contest(contest)
                .accountName("큐 계좌 " + suffix)
                .cash(INITIAL_CASH)
                .holdAmount(PRICE)
                .isDefault(false)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("Q" + suffix).name("큐종목 " + suffix).marketType("KOSPI").build());

        Order order = orderRepository.save(Order.createLimitOrder(account, stock, PRICE, 1, OrderMethod.BUY));
        orderHoldRepository.save(OrderHold.create(order, account, PRICE));
        return new BuyOrder(order.getOrderId(), account.getAccountId(), stock.getCode());
    }
}
