package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 컨테이너·프로파일 설정은 IntegrationTestSupport 싱글턴을 상속 — 자체 @Container 선언은
// reuse 활성 환경에서 같은 해시의 공유 컨테이너를 클래스 종료 시 stop시켜, 캐시된 다른
// 테스트 컨텍스트를 전멸시키는 원인이었다(동일 설정이라 동작은 그대로).
@DisplayName("LimitOrderFillCoordinator 동시성 제어 테스트 (통합 테스트)")
class LimitOrderFillCoordinatorConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private LimitOrderFillCoordinator limitOrderFillCoordinator;

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
    private OrderBookRepository orderBookRepository;

    @Autowired
    private OrderHoldRepository orderHoldRepository;

    @Autowired
    private AccountStockRepository accountStockRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Member testMember;
    private Contest testContest;
    private Account testAccount;
    private Stock testStock;
    // 워터마크는 테스트 사이에 지우지 않는다. 테스트마다 다른 토픽 이름을 써서 서로의 위치에 걸리지 않게 한다.
    private String topic;

    @BeforeEach
    void setUp() {
        // Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 테스트 데이터 생성 (각 테스트마다 고유한 데이터 생성)
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        topic = "matching.commands-" + uniqueId;
        testMember = Member.builder()
                .name("테스트 사용자 " + uniqueId)
                .email("test" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build();
        testMember = memberRepository.save(testMember);

        testContest = Contest.builder()
                .contestName("테스트 대회 " + uniqueId)
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10000000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build();
        testContest = contestRepository.save(testContest);

        testAccount = Account.builder()
                .member(testMember)
                .contest(testContest)
                .accountName("테스트 계좌 " + uniqueId)
                .cash(new BigDecimal("100000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build();
        testAccount = accountRepository.save(testAccount);

        // Stock은 이미 존재할 수 있으므로 확인 후 생성
        testStock = stockRepository.findById("005930")
                .orElseGet(() -> {
                    Stock stock = Stock.builder()
                            .code("005930")
                            .name("삼성전자")
                            .build();
                    return stockRepository.save(stock);
                });
    }

    // 테스트용 주문을 별도 트랜잭션으로 커밋한다.
    // 오더북에 따로 등록하지 않는다. 주문 행이 곧 오더북이라 커밋으로 끝난다.
    private Order createAndSaveOrder(String stockCode, OrderMethod orderMethod, BigDecimal price, int quantity) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = transactionManager.getTransaction(def);
        
        try {
            Stock stock = stockRepository.findById(stockCode)
                    .orElseThrow(() -> new IllegalArgumentException("Stock not found: " + stockCode));

            Order order = Order.createLimitOrder(testAccount, stock, price, quantity, orderMethod);
            order = orderRepository.save(order);

            // OrderHold 생성 (매수 주문인 경우)
            if (orderMethod == OrderMethod.BUY) {
                BigDecimal holdAmount = price.multiply(new BigDecimal(quantity));
                testAccount.increaseHoldAmount(holdAmount);
                testAccount = accountRepository.save(testAccount);

                OrderHold orderHold = OrderHold.create(order, testAccount, holdAmount);
                orderHoldRepository.save(orderHold);
            } else {
                // 매도 주문인 경우 AccountStock 생성 및 홀딩
                AccountStock accountStock = accountStockRepository.findByAccountAndStock(testAccount, stock)
                        .orElseGet(() -> {
                            AccountStock newAccountStock = AccountStock.create(testAccount, stock, quantity, price);
                            return accountStockRepository.save(newAccountStock);
                        });
                accountStock.increaseHoldQuantity(quantity);
                accountStockRepository.save(accountStock);
            }

            transactionManager.commit(status);
            return order;
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    @Test
    @DisplayName("체결 이벤트 하나가 대응하는 주문을 체결시킨다")
    void match_singleEvent_fillsOrder() {
        // Given
        String stockCode = "005930";
        Order order = createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);

        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        // When
        int filledOrders = limitOrderFillCoordinator.processQueuedFill(stockCode, event, position(0)).filledOrders();

        // Then
        assertThat(filledOrders).isEqualTo(1);
        Order reloaded = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(reloaded.getFilledQuantity()).isEqualTo(10);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    @DisplayName("같은 위치의 명령이 다시 오면 건너뛴다 — 틱 하나가 두 주문을 체결시키지 않는다")
    void queuedFill_redelivered_skipped() {
        // Given: 틱 하나(10주)로 딱 하나만 체결될 주문 둘.
        // 재전달을 막지 못하면 첫 번째로 A, 두 번째로 B 가 체결되어 틱 10주로 20주가 체결된다.
        String stockCode = "005930";
        Order first = createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        Order second = createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        LimitOrderFillEvent event = sellEvent();
        CommandPosition position = position(42L);

        // When
        var firstDelivery = limitOrderFillCoordinator.processQueuedFill(stockCode, event, position);
        var redelivery = limitOrderFillCoordinator.processQueuedFill(stockCode, event, position);

        // Then
        assertThat(firstDelivery.duplicate()).isFalse();
        assertThat(firstDelivery.filledOrders()).isEqualTo(1);
        assertThat(redelivery.duplicate()).isTrue();
        assertThat(redelivery.filledOrders()).isZero();
        assertThat(executionRepository.findByOrderIdInWithOrder(List.of(first.getOrderId(), second.getOrderId())))
                .as("틱 하나로 체결은 한 건")
                .hasSize(1);
    }

    @Test
    @DisplayName("맞는 주문이 없던 명령도 위치를 기록한다 — 재전달 때 그사이 들어온 주문과 체결되지 않는다")
    void queuedFill_noMatchThenRedelivered_skipped() {
        String stockCode = "005930";
        LimitOrderFillEvent event = sellEvent();
        CommandPosition position = position(7L);

        var firstDelivery = limitOrderFillCoordinator.processQueuedFill(stockCode, event, position);
        Order lateOrder = createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        var redelivery = limitOrderFillCoordinator.processQueuedFill(stockCode, event, position);

        assertThat(firstDelivery.filledOrders()).isZero();
        assertThat(redelivery.duplicate()).isTrue();
        assertThat(orderRepository.findById(lateOrder.getOrderId()).orElseThrow().getFilledQuantity()).isZero();
    }

    @Test
    @DisplayName("같은 위치를 두 워커가 동시에 처리해도 한쪽만 체결한다 (리밸런싱 좀비)")
    void queuedFill_sameTimeSamePosition_onlyOneFills() throws InterruptedException {
        String stockCode = "005930";
        Order first = createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        Order second = createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        LimitOrderFillEvent event = sellEvent();
        CommandPosition position = position(99L);

        int workers = 2;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger duplicates = new AtomicInteger();
        List<Exception> exceptions = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < workers; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (limitOrderFillCoordinator.processQueuedFill(stockCode, event, position).duplicate()) {
                        duplicates.incrementAndGet();
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertThat(exceptions).isEmpty();
        assertThat(duplicates.get()).isEqualTo(1);
        assertThat(executionRepository.findByOrderIdInWithOrder(List.of(first.getOrderId(), second.getOrderId())))
                .hasSize(1);
    }

    private CommandPosition position(long offset) {
        return new CommandPosition(topic, 0, offset);
    }

    private LimitOrderFillEvent sellEvent() {
        return new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );
    }
}
