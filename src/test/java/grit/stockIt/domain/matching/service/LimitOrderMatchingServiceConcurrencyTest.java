package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
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
@DisplayName("LimitOrderMatchingService 동시성 제어 테스트 (통합 테스트)")
class LimitOrderMatchingServiceConcurrencyTest extends IntegrationTestSupport {

    @Autowired
    private LimitOrderMatchingService limitOrderMatchingService;

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

    @BeforeEach
    void setUp() {
        // Redis 데이터 초기화
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 테스트 데이터 생성 (각 테스트마다 고유한 데이터 생성)
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
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
        var executions = limitOrderMatchingService.match(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        Order reloaded = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(reloaded.getFilledQuantity()).isEqualTo(10);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.FILLED);
    }

    @Test
    @DisplayName("같은 종목에 동시에 들어온 이벤트가 모두 처리되고 초과 체결이 없다")
    void match_concurrentEvents_allProcessedWithoutOverfill() throws InterruptedException {
        // Given: 이벤트 하나당 딱 맞게 체결될 주문을 같은 수만큼 준비한다.
        // 직렬화가 깨지면 같은 주문에 두 번 배분되어 체결 총량이 주문 총량을 넘는다.
        String stockCode = "005930";
        int eventCount = 50;

        List<Long> orderIds = new ArrayList<>();
        BigDecimal initialCash = testAccount.getCash();
        for (int i = 0; i < eventCount; i++) {
            orderIds.add(createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10).getOrderId());
        }

        CountDownLatch ready = new CountDownLatch(eventCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(eventCount);
        AtomicInteger processedEvents = new AtomicInteger();
        AtomicInteger totalExecutions = new AtomicInteger();
        List<Exception> exceptions = java.util.Collections.synchronizedList(new ArrayList<>());

        // When: 모든 스레드를 같은 순간에 풀어 락 경합을 강제한다.
        // 큐가 없으므로 각 스레드는 자기 이벤트를 직접 들고 들어가고, 락을 못 잡으면 기다린다.
        for (int i = 0; i < eventCount; i++) {
            new Thread(() -> {
                LimitOrderFillEvent event = new LimitOrderFillEvent(
                        UUID.randomUUID().toString(),
                        OrderMethod.SELL,
                        new BigDecimal("100"),
                        10,
                        Instant.now().toEpochMilli()
                );
                ready.countDown();
                try {
                    start.await();
                    var executions = limitOrderMatchingService.match(stockCode, event);
                    processedEvents.incrementAndGet();
                    totalExecutions.addAndGet(executions.size());
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        ready.await();
        start.countDown();
        done.await();

        // Then
        assertThat(exceptions)
                .as("락을 기다리는 구조이므로 포기하거나 실패하는 이벤트가 없어야 한다")
                .isEmpty();
        assertThat(processedEvents.get()).isEqualTo(eventCount);

        // ===== 데이터 정합성 (갱신 손실·초과 체결 방지) =====
        List<Order> orders = orderRepository.findAllById(orderIds);
        assertThat(orders).hasSize(eventCount);

        int totalFilledQuantity = 0;
        for (Order order : orders) {
            assertThat(order.getFilledQuantity())
                    .as("주문 %d 의 체결 수량", order.getOrderId())
                    .isEqualTo(10);
            assertThat(order.getRemainingQuantity()).isZero();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
            totalFilledQuantity += order.getFilledQuantity();
        }
        assertThat(totalFilledQuantity)
                .as("총 체결 수량은 주문 총량과 정확히 같아야 한다(초과 체결 없음)")
                .isEqualTo(eventCount * 10);

        var executions = executionRepository.findByOrderIdInWithOrder(orderIds);
        assertThat(executions).hasSize(eventCount);
        assertThat(executions.stream().mapToInt(e -> e.getQuantity()).sum()).isEqualTo(eventCount * 10);
        assertThat(totalExecutions.get()).isEqualTo(eventCount);

        Account updatedAccount = accountRepository.findById(testAccount.getAccountId()).orElseThrow();
        assertThat(updatedAccount.getCash())
                .as("현금이 정확히 차감되어야 한다 (50건 × 10주 × 100원)")
                .isEqualByComparingTo(initialCash.subtract(new BigDecimal("50000")));

        AccountStock accountStock = accountStockRepository.findByAccountAndStock(updatedAccount, testStock)
                .orElseThrow(() -> new AssertionError("AccountStock이 생성되어야 한다"));
        assertThat(accountStock.getQuantity()).isEqualTo(eventCount * 10);
    }
}
