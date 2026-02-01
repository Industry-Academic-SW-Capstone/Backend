package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@TestPropertySource(properties = "spring.task.scheduling.enabled=false")
@DisplayName("LimitOrderMatchingService 동시성 제어 테스트 (통합 테스트)")
class LimitOrderMatchingServiceConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
            .withDatabaseName("test_database")
            .withUsername("test_user")
            .withPassword("test_password")
            .withReuse(true);  // 컨테이너 재사용으로 성능 향상

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379)
            .withReuse(true);  // 컨테이너 재사용으로 성능 향상

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL 설정
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        
        // Redis 설정
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

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
    private RedisOrderBookRepository redisOrderBookRepository;

    @Autowired
    private OrderHoldRepository orderHoldRepository;

    @Autowired
    private AccountStockRepository accountStockRepository;

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

    /**
     * 테스트용 주문 생성 및 Redis 오더북에 추가 (명시적 트랜잭션 관리)
     */
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
            
            // Redis 오더북에 추가 (트랜잭션 외부에서)
            redisOrderBookRepository.addOrder(order);

            return order;
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    @Test
    @DisplayName("동시성 제어 성공 시 이벤트를 처리한다")
    void consumeNextEvent_LockAcquired_ProcessesEvent() {
        // Given
        String stockCode = "005930";
        String queueKey = "sim:limit:event:" + stockCode;
        
        // 매수 주문 생성 및 Redis 오더북에 추가
        createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        // Redis 큐에 이벤트 추가
        try {
            String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(event);
            redisTemplate.opsForList().rightPush(queueKey, payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // When
        List<grit.stockIt.domain.execution.entity.Execution> executions = 
                limitOrderMatchingService.consumeNextEvent(stockCode);

        // Then
        // 이벤트가 처리되었으므로 큐에서 제거됨
        String remaining = redisTemplate.opsForList().leftPop(queueKey);
        assertThat(remaining).isNull();
        // 실행 결과가 있어야 함
        assertThat(executions).isNotEmpty();
    }

    @Test
    @DisplayName("동시성 제어 획득 실패 시 빈 리스트를 반환한다")
    void consumeNextEvent_LockAcquisitionFailed_ReturnsEmpty() {
        // Given
        String stockCode = "005930";
        String lockKey = "sim:limit:lock:" + stockCode;
        
        // 다른 프로세스가 락을 먼저 획득한 상황 시뮬레이션
        redisTemplate.opsForValue().set(lockKey, "existing-lock-token");

        // When
        List<grit.stockIt.domain.execution.entity.Execution> executions = 
                limitOrderMatchingService.consumeNextEvent(stockCode);

        // Then
        assertThat(executions).isEmpty();
    }

    @Test
    @DisplayName("여러 스레드가 동시에 접근해도 동시성 제어로 인해 한 번만 처리된다")
    void consumeNextEvent_ConcurrentAccess_OnlyOneProcesses() throws InterruptedException {
        // Given
        String stockCode = "005930";
        String queueKey = "sim:limit:event:" + stockCode;
        
        // 매수 주문 생성 및 Redis 오더북에 추가
        createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        
        // 큐에 이벤트 1개만 추가
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        try {
            String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(event);
            redisTemplate.opsForList().rightPush(queueKey, payload);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        int threadCount = 50; // 적절한 스레드 수 (실제 운영 환경과 유사)
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger emptyCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        // When - 여러 스레드가 동시에 접근
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    List<grit.stockIt.domain.execution.entity.Execution> executions = 
                            limitOrderMatchingService.consumeNextEvent(stockCode);
                    if (!executions.isEmpty()) {
                        successCount.incrementAndGet();
                    } else {
                        emptyCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // Then
        // 디버깅: 예외가 있으면 출력
        if (!exceptions.isEmpty()) {
            System.err.println("=== 예외 발생 (" + exceptions.size() + "개) ===");
            exceptions.stream()
                    .collect(java.util.stream.Collectors.groupingBy(e -> e.getClass().getSimpleName(), java.util.stream.Collectors.counting()))
                    .forEach((type, count) -> System.err.println("  " + type + ": " + count + "개"));
            // 첫 번째 예외만 상세 출력
            if (!exceptions.isEmpty()) {
                System.err.println("첫 번째 예외 상세:");
                exceptions.get(0).printStackTrace();
            }
        }
        
        // threadCount개 스레드 중 1개만 성공해야 함 (동시성 제어로 인해)
        assertThat(successCount.get())
                .as("동시성 제어로 인해 1개 스레드만 성공해야 함 (successCount: %d, emptyCount: %d, exceptions: %d)", 
                    successCount.get(), emptyCount.get(), exceptions.size())
                .isEqualTo(1);
        assertThat(emptyCount.get()).isEqualTo(threadCount - 1);
        assertThat(exceptions)
                .as("예외가 발생하지 않아야 함 (발생한 예외: %d개)", exceptions.size())
                .isEmpty();
        
        // 큐가 비어있어야 함 (1개만 처리되었으므로)
        String remaining = redisTemplate.opsForList().leftPop(queueKey);
        assertThat(remaining).isNull();
    }

    @Test
    @DisplayName("동시성 제어가 해제되면 다음 스레드가 처리할 수 있다")
    void consumeNextEvent_LockReleased_NextThreadCanAcquire() throws InterruptedException {
        // Given
        String stockCode = "005930";
        String queueKey = "sim:limit:event:" + stockCode;
        
        // 매수 주문 2개 생성 및 Redis 오더북에 추가
        createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        createAndSaveOrder(stockCode, OrderMethod.BUY, new BigDecimal("100"), 10);
        
        // 큐에 이벤트 2개 추가
        for (int i = 0; i < 2; i++) {
            LimitOrderFillEvent event = new LimitOrderFillEvent(
                    UUID.randomUUID().toString(),
                    OrderMethod.SELL,
                    new BigDecimal("100"),
                    10,
                    Instant.now().toEpochMilli()
            );

            try {
                String payload = new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(event);
                redisTemplate.opsForList().rightPush(queueKey, payload);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        // When - 순차적으로 접근 (첫 번째가 끝나면 두 번째가 락 획득)
        new Thread(() -> {
            try {
                List<grit.stockIt.domain.execution.entity.Execution> executions = 
                        limitOrderMatchingService.consumeNextEvent(stockCode);
                if (!executions.isEmpty()) {
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        }).start();

        // 첫 번째 스레드가 완료될 때까지 대기
        Thread.sleep(500);

        new Thread(() -> {
            try {
                List<grit.stockIt.domain.execution.entity.Execution> executions = 
                        limitOrderMatchingService.consumeNextEvent(stockCode);
                if (!executions.isEmpty()) {
                    successCount.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        }).start();

        latch.await();

        // Then
        // 두 스레드 모두 성공해야 함 (순차적으로 동시성 제어 획득)
        assertThat(successCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("큐가 비어있으면 빈 리스트를 반환한다")
    void consumeNextEvent_EmptyQueue_ReturnsEmpty() {
        // Given
        String stockCode = "005930";
        // 큐에 아무것도 없음

        // When
        List<grit.stockIt.domain.execution.entity.Execution> executions = 
                limitOrderMatchingService.consumeNextEvent(stockCode);

        // Then
        assertThat(executions).isEmpty();
    }
}

