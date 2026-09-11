package grit.stockIt.domain.matching.service;

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
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 큐 드레인 워커 검증.
 *
 * <p><b>왜 이 테스트가 있나.</b> 예전에는 큐를 소비하는 경로가 발행 시점 하나뿐이라,
 * 락을 못 잡으면 이벤트가 큐에 남고 <b>유입이 멈추면 영구히 방치됐다.</b> 부하 측정에서
 * 유입 120/s 중 46/s만 체결되고 3,786건이 큐에 남은 채 끝났다. 지연·에러율로는 전혀
 * 드러나지 않았다 — 락 실패가 빠르게 200을 반환하기 때문이다.
 *
 * <p>워커 빈은 테스트 프로파일에서 꺼져 있다({@code matching.worker.enabled=false}).
 * 큐 잔여를 동기적으로 단언하는 다른 테스트들과 비동기 드레인이 경쟁하기 때문이다.
 * 그래서 여기서는 디스패처를 직접 만들어 기동한다.
 */
class MatchingEventDispatcherTest extends IntegrationTestSupport {

    private static final long AWAIT_TIMEOUT_MILLIS = 10_000L;
    private static final long AWAIT_POLL_MILLIS = 50L;

    @Autowired
    private LimitOrderMatchingService limitOrderMatchingService;
    @Autowired
    private MatchingEventQueue matchingEventQueue;
    @Autowired
    private OrderBookStore orderBookStore;
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
    private StringRedisTemplate redisTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private MatchingEventDispatcher dispatcher;
    private String stockCode;
    private Account testAccount;
    private Stock testStock;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        stockCode = "W" + uniqueId;

        Member member = memberRepository.save(Member.builder()
                .name("워커 테스트 사용자 " + uniqueId)
                .email("worker" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("워커 테스트 대회 " + uniqueId)
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10000000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        testAccount = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("워커 테스트 계좌 " + uniqueId)
                .cash(new BigDecimal("100000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());
        testStock = stockRepository.save(Stock.builder()
                .code(stockCode)
                .name("워커 테스트 종목")
                .build());

        dispatcher = new MatchingEventDispatcher(limitOrderMatchingService);
        ReflectionTestUtils.setField(dispatcher, "enabled", true);
        ReflectionTestUtils.setField(dispatcher, "workerThreads", 2);
        ReflectionTestUtils.setField(dispatcher, "sweepIntervalMillis", 50L);
        dispatcher.start();
    }

    @AfterEach
    void tearDownDispatcher() {
        if (dispatcher != null) {
            dispatcher.stop();
        }
    }

    @Test
    @DisplayName("큐에 쌓인 이벤트를 빌 때까지 소비한다 — 유입이 멈춰도 방치되지 않는다")
    void drainsQueueUntilEmpty() {
        saveBuyOrderInOrderBook(new BigDecimal("70000"), 30);

        // 발행 경로를 거치지 않고 큐에만 쌓는다. 예전 구조라면 이 셋은 영원히 남는다.
        for (int i = 0; i < 3; i++) {
            matchingEventQueue.enqueue(stockCode, new LimitOrderFillEvent(
                    "stranded-" + i, OrderMethod.SELL, new BigDecimal("70000"), 10,
                    System.currentTimeMillis()));
        }
        assertThat(matchingEventQueue.size(stockCode)).isEqualTo(3);

        dispatcher.requestDrain(stockCode);

        awaitQueueEmpty();
        assertThat(matchingEventQueue.size(stockCode))
                .as("워커가 큐를 끝까지 비워야 한다")
                .isZero();
    }

    @Test
    @DisplayName("큐가 비어 있으면 재시도하지 않는다 — 스위퍼가 무한 루프에 빠지지 않는다")
    void doesNotRetryOnEmptyQueue() {
        dispatcher.requestDrain(stockCode);

        sleepQuietly(sweepCycles(4));

        assertThat(matchingEventQueue.size(stockCode)).isZero();
        // pending에 남아 있으면 스위퍼가 계속 작업을 제출한다. 비어 있어야 정상이다.
        @SuppressWarnings("unchecked")
        java.util.Set<String> pending =
                (java.util.Set<String>) ReflectionTestUtils.getField(dispatcher, "pending");
        assertThat(pending)
                .as("큐 고갈로 끝난 종목은 재시도 대상에서 빠져야 한다")
                .doesNotContain(stockCode);
    }

    private void awaitQueueEmpty() {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (matchingEventQueue.size(stockCode) == 0) {
                return;
            }
            sleepQuietly(AWAIT_POLL_MILLIS);
        }
        throw new AssertionError("제한 시간 안에 큐가 비지 않았다. 잔여="
                + matchingEventQueue.size(stockCode));
    }

    private static long sweepCycles(int count) {
        return AWAIT_POLL_MILLIS * count;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }

    private void saveBuyOrderInOrderBook(BigDecimal price, int quantity) {
        Order order = runInTransaction(() -> {
            Order saved = orderRepository.save(
                    Order.createLimitOrder(testAccount, testStock, price, quantity, OrderMethod.BUY));
            BigDecimal holdAmount = price.multiply(BigDecimal.valueOf(quantity));
            Account account = accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            account.increaseHoldAmount(holdAmount);
            orderHoldRepository.save(OrderHold.create(saved, account, holdAmount));
            return saved;
        });
        orderBookStore.addOrder(orderRepository.findById(order.getOrderId()).orElseThrow());
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
