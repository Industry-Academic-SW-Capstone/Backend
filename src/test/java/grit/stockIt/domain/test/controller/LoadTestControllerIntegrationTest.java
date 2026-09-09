package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.MatchingEventQueue;
import grit.stockIt.domain.matching.repository.OrderBookStore;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.test.dto.MockExecutionRequest;
import grit.stockIt.domain.test.dto.MockExecutionResponse;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부하 테스트 진입점({@code /api/test/mock-execution}) 검증.
 *
 * <p>이 엔드포인트가 <b>실제 매칭 경로(큐 → 락 → 정산)를 타는지</b>가 측정 전체의 전제다.
 * 예전 구현은 {@code distributeEvent}를 직접 호출해 큐와 락을 건너뛰었고, 그 상태로 재면
 * 종목별 직렬화가 측정에서 빠져 단일 종목 처리량이 실제보다 높게 나온다.
 *
 * <p>HTTP 계층이 아니라 컨트롤러 빈을 직접 호출한다 — 검증 대상이 요청 매핑이 아니라
 * 호출 경로이기 때문이다.
 */
@DisplayName("부하 테스트 진입점 (통합 테스트)")
class LoadTestControllerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private LoadTestController loadTestController;

    @Autowired
    private MatchingEventQueue matchingEventQueue;

    @Autowired
    private OrderBookStore orderBookStore;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderHoldRepository orderHoldRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String stockCode;
    private Account testAccount;
    private Stock testStock;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        stockCode = "L" + uniqueId;

        Member member = memberRepository.save(Member.builder()
                .name("부하 테스트 사용자 " + uniqueId)
                .email("load" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());

        Contest contest = contestRepository.save(Contest.builder()
                .contestName("부하 테스트 대회 " + uniqueId)
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10000000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());

        testAccount = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("부하 테스트 계좌 " + uniqueId)
                .cash(new BigDecimal("100000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());

        testStock = stockRepository.save(Stock.builder()
                .code(stockCode)
                .name("테스트종목 " + uniqueId)
                .build());
    }

    @Test
    @DisplayName("주입한 체결 이벤트가 매칭까지 실행되고 결과가 응답에 담긴다")
    void injectsEventAndReturnsMatchingResult() {
        Order buyOrder = saveBuyOrderInOrderBook(new BigDecimal("70000"), 10);

        ResponseEntity<MockExecutionResponse> response =
                loadTestController.injectMockExecution(request(OrderMethod.SELL, new BigDecimal("70000"), 10));

        MockExecutionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.executionCount()).isEqualTo(1);
        assertThat(body.queueDepth()).isZero();
        assertThat(body.durationMs()).isNotNegative();

        assertThat(orderRepository.findById(buyOrder.getOrderId()).orElseThrow().getRemainingQuantity())
                .isZero();
    }

    @Test
    @DisplayName("이벤트가 큐를 거쳐 처리된다 — 먼저 쌓인 이벤트가 먼저 소비된다")
    void routesThroughQueueInFifoOrder() {
        saveBuyOrderInOrderBook(new BigDecimal("70000"), 10);

        // 요청보다 먼저 큐에 들어 있던 이벤트. 큐를 실제로 거친다면 이쪽이 먼저 소비된다.
        matchingEventQueue.enqueue(stockCode, new LimitOrderFillEvent(
                "pre-existing", OrderMethod.SELL, new BigDecimal("70000"), 4, System.currentTimeMillis()));

        ResponseEntity<MockExecutionResponse> response =
                loadTestController.injectMockExecution(request(OrderMethod.SELL, new BigDecimal("70000"), 10));

        MockExecutionResponse body = response.getBody();
        assertThat(body).isNotNull();

        // 선행 이벤트(4주)가 체결되고, 요청으로 넣은 이벤트는 큐에 남아야 한다.
        // 큐를 건너뛰는 구현이라면 요청 이벤트가 바로 처리되어 잔여가 0이 된다.
        assertThat(body.queueDepth())
                .as("요청 이벤트가 큐에 남아 있어야 큐를 거친 것이다")
                .isEqualTo(1);
        assertThat(matchingEventQueue.dequeue(stockCode).quantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("체결 상대가 없으면 체결 0건으로 응답한다")
    void reportsZeroExecutionsWhenNoCounterparty() {
        ResponseEntity<MockExecutionResponse> response =
                loadTestController.injectMockExecution(request(OrderMethod.SELL, new BigDecimal("70000"), 10));

        MockExecutionResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.executionCount()).isZero();
        assertThat(body.queueDepth()).isZero();
    }

    // ── 헬퍼 ──

    private MockExecutionRequest request(OrderMethod method, BigDecimal price, int quantity) {
        return new MockExecutionRequest(
                stockCode, UUID.randomUUID().toString(), method, price, quantity, System.currentTimeMillis());
    }

    /** 정산 경로가 OrderHold를 조회하므로 매수 주문은 홀딩까지 만들어 둔다. */
    private Order saveBuyOrderWithHold(BigDecimal price, int quantity) {
        return runInTransaction(() -> {
            Order order = orderRepository.save(
                    Order.createLimitOrder(testAccount, testStock, price, quantity, OrderMethod.BUY));

            BigDecimal holdAmount = price.multiply(BigDecimal.valueOf(quantity));
            Account account = accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            account.increaseHoldAmount(holdAmount);
            orderHoldRepository.save(OrderHold.create(order, account, holdAmount));
            return order;
        });
    }

    /**
     * 오더북에 등록한다. 기본 백엔드(redis)는 주문 저장과 오더북이 분리돼 있어
     * DB에 저장하는 것만으로는 매칭 후보가 되지 않는다.
     * (jpa 백엔드에서는 no-op이므로 어느 구성에서도 안전하다.)
     */
    private Order saveBuyOrderInOrderBook(BigDecimal price, int quantity) {
        Order order = saveBuyOrderWithHold(price, quantity);
        orderBookStore.addOrder(orderRepository.findById(order.getOrderId()).orElseThrow());
        return order;
    }

    private <T> T runInTransaction(Supplier<T> action) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            T result = action.get();
            transactionManager.commit(status);
            return result;
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
            throw e;
        }
    }
}
