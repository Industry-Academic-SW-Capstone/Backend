package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.service.LimitOrderMatchingService;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDB 오더북({@link JpaOrderBookStore}) + advisory 락 백엔드 검증.
 *
 * <p>벤치마크의 전제는 "두 백엔드가 같은 후보를 같은 우선순위로 돌려준다"는 것이다.
 * 그게 깨지면 성능 비교는 의미가 없으므로, 계약 검증과 함께 Redis 구현과의 동치성도 확인한다.
 *
 * <p>테스트 환경은 Flyway가 꺼져 있으므로({@code spring.flyway.enabled=false}) 실제
 * 마이그레이션 파일을 {@code @Sql}로 직접 적용한다 — 인덱스 DDL 자체의 유효성도 함께 검증된다.
 */
@DisplayName("RDB 오더북 저장소 (통합 테스트)")
@TestPropertySource(properties = "matching.orderbook.backend=jpa")
@Sql(scripts = "classpath:db/migration/V10__add_orderbook_index.sql")
class JpaOrderBookStoreIntegrationTest extends IntegrationTestSupport {

    /** 테스트마다 새 종목을 쓴다 — 오더북 조회가 종목 단위라 다른 테스트의 주문과 섞이지 않는다.
     *  (주문은 execution·order_hold가 참조하므로 일괄 삭제로 격리할 수 없다.) */
    private String stockCode;

    @Autowired
    private OrderBookStore orderBookStore;

    @Autowired
    private LimitOrderMatchingService limitOrderMatchingService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

    private Account testAccount;
    private Stock testStock;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        stockCode = "T" + uniqueId;

        Member member = memberRepository.save(Member.builder()
                .name("테스트 사용자 " + uniqueId)
                .email("test" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());

        Contest contest = contestRepository.save(Contest.builder()
                .contestName("테스트 대회 " + uniqueId)
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10000000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());

        testAccount = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("테스트 계좌 " + uniqueId)
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
    @DisplayName("선택된 백엔드는 RDB 오더북 구현이다")
    void backendIsJpaImplementation() {
        assertThat(orderBookStore).isInstanceOf(JpaOrderBookStore.class);
    }

    @Test
    @DisplayName("매도 후보는 낮은 가격이 먼저, 같은 가격이면 먼저 접수된 주문이 먼저 나온다")
    void sellCandidatesFollowPriceThenTimePriority() {
        Order expensive = saveOrder(OrderMethod.SELL, new BigDecimal("70200"), 10);
        Order cheapFirst = saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);
        Order cheapSecond = saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);

        List<OrderBookEntry> entries = orderBookStore.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("70500"), 100);

        assertThat(entries).extracting(OrderBookEntry::orderId)
                .containsExactly(cheapFirst.getOrderId(), cheapSecond.getOrderId(), expensive.getOrderId());
    }

    @Test
    @DisplayName("매수 후보는 높은 가격이 먼저, 같은 가격이면 먼저 접수된 주문이 먼저 나온다")
    void buyCandidatesFollowPriceThenTimePriority() {
        Order cheap = saveOrder(OrderMethod.BUY, new BigDecimal("69800"), 10);
        Order expensiveFirst = saveOrder(OrderMethod.BUY, new BigDecimal("70000"), 10);
        Order expensiveSecond = saveOrder(OrderMethod.BUY, new BigDecimal("70000"), 10);

        List<OrderBookEntry> entries = orderBookStore.fetchMatchingEntries(
                stockCode, OrderMethod.SELL, new BigDecimal("69500"), 100);

        assertThat(entries).extracting(OrderBookEntry::orderId)
                .containsExactly(expensiveFirst.getOrderId(), expensiveSecond.getOrderId(), cheap.getOrderId());
    }

    @Test
    @DisplayName("체결가 범위를 벗어난 주문은 후보에서 제외된다")
    void candidatesOutsidePriceLimitAreExcluded() {
        Order inRange = saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("71000"), 10);

        List<OrderBookEntry> entries = orderBookStore.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("70000"), 100);

        assertThat(entries).extracting(OrderBookEntry::orderId).containsExactly(inRange.getOrderId());
    }

    @Test
    @DisplayName("취소·전량체결 주문은 오더북에서 빠지고, 부분체결 주문은 잔여 수량으로 남는다")
    void inactiveOrdersDropOutAndPartialFillsKeepRemaining() {
        Order cancelled = saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);
        Order filled = saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);
        Order partial = saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);

        runInTransaction(() -> {
            orderRepository.findById(cancelled.getOrderId()).orElseThrow().markCancelled();
            orderRepository.findById(filled.getOrderId()).orElseThrow().applyFill(10);
            orderRepository.findById(partial.getOrderId()).orElseThrow().applyFill(4);
        });

        List<OrderBookEntry> entries = orderBookStore.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("70500"), 100);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).orderId()).isEqualTo(partial.getOrderId());
        assertThat(entries.get(0).remainingQuantity()).isEqualTo(6);
        assertThat(entries.get(0).totalQuantity()).isEqualTo(10);

        assertThat(orderBookStore.exists(partial.getOrderId(), stockCode, OrderMethod.SELL)).isTrue();
        assertThat(orderBookStore.exists(cancelled.getOrderId(), stockCode, OrderMethod.SELL)).isFalse();
        assertThat(orderBookStore.exists(filled.getOrderId(), stockCode, OrderMethod.SELL)).isFalse();
    }

    @Test
    @DisplayName("maxOrders만큼만 후보를 가져온다")
    void fetchRespectsMaxOrders() {
        for (int i = 0; i < 5; i++) {
            saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 10);
        }

        List<OrderBookEntry> entries = orderBookStore.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("70500"), 3);

        assertThat(entries).hasSize(3);
    }

    @Test
    @DisplayName("Redis 구현과 동일한 후보 집합을 돌려준다 — 벤치마크 동치성 전제")
    void returnsSameCandidatesAsRedisImplementation() {
        List<Order> orders = List.of(
                saveOrder(OrderMethod.SELL, new BigDecimal("70200"), 7),
                saveOrder(OrderMethod.SELL, new BigDecimal("70000"), 5),
                saveOrder(OrderMethod.SELL, new BigDecimal("70100"), 3)
        );

        OrderBookStore redisStore = new RedisOrderBookRepository(redisTemplate);
        orders.forEach(order -> redisStore.addOrder(orderRepository.findById(order.getOrderId()).orElseThrow()));

        List<OrderBookEntry> fromJpa = orderBookStore.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("70500"), 100);
        List<OrderBookEntry> fromRedis = redisStore.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("70500"), 100);

        // Redis ZSet은 동일 가격 내 정렬을 보장하지 않아 호출자가 다시 정렬한다.
        // 따라서 순서가 아니라 "같은 주문을 같은 잔여 수량으로" 돌려주는지를 본다.
        assertThat(fromJpa)
                .extracting(OrderBookEntry::orderId, OrderBookEntry::price, OrderBookEntry::remainingQuantity)
                .containsExactlyInAnyOrderElementsOf(
                        fromRedis.stream()
                                .map(e -> org.assertj.core.groups.Tuple.tuple(e.orderId(), e.price(), e.remainingQuantity()))
                                .toList());
    }

    @Test
    @DisplayName("advisory 락 + RDB 오더북으로 체결 이벤트가 끝까지 처리된다")
    void matchesEndToEndWithAdvisoryLock() {
        Order buyOrder = saveBuyOrderWithHold(new BigDecimal("70000"), 10);

        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("70000"),
                10,
                Instant.now().toEpochMilli()
        );
        pushEvent(event);

        var executions = limitOrderMatchingService.consumeNextEvent(stockCode);

        assertThat(executions).hasSize(1);
        assertThat(orderRepository.findById(buyOrder.getOrderId()).orElseThrow().getRemainingQuantity()).isZero();
        // 체결로 오더북에서 빠졌는지는 상태로 확인한다(RDB 백엔드는 별도 오더북 자료구조가 없다).
        assertThat(orderBookStore.exists(buyOrder.getOrderId(), stockCode, OrderMethod.BUY)).isFalse();
    }

    // ── 픽스처 헬퍼 ──

    private Order saveOrder(OrderMethod orderMethod, BigDecimal price, int quantity) {
        return runInTransaction(() -> orderRepository.save(
                Order.createLimitOrder(testAccount, testStock, price, quantity, orderMethod)));
    }

    /** 정산 경로가 OrderHold를 조회하므로, 종단 테스트용 매수 주문은 홀딩까지 만들어 둔다. */
    private Order saveBuyOrderWithHold(BigDecimal price, int quantity) {
        return runInTransaction(() -> {
            Order order = orderRepository.save(
                    Order.createLimitOrder(testAccount, testStock, price, quantity, OrderMethod.BUY));

            BigDecimal holdAmount = price.multiply(BigDecimal.valueOf(quantity));
            Account account = accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            account.increaseHoldAmount(holdAmount);
            orderHoldRepository.save(OrderHold.create(order, account, holdAmount));

            // 매수 체결 시 AccountStock은 정산 로직이 직접 생성하므로 미리 만들지 않는다.
            return order;
        });
    }

    private void pushEvent(LimitOrderFillEvent event) {
        try {
            redisTemplate.opsForList().rightPush("sim:limit:event:" + stockCode, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException("테스트 이벤트 직렬화 실패", e);
        }
    }

    private <T> T runInTransaction(java.util.function.Supplier<T> action) {
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

    private void runInTransaction(Runnable action) {
        runInTransaction(() -> {
            action.run();
            return null;
        });
    }
}
