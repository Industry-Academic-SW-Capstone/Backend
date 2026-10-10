package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
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
import grit.stockIt.global.support.KafkaIntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 종목 락 없이 "같은 종목의 명령은 한 파티션에서 워커 하나가 차례로 처리한다"만으로 오더북과 계좌가 맞는지 본다.
// 한 종목에 체결·취소·만료를 여러 스레드가 한꺼번에 넣고, 다 처리된 뒤 장부 불변식을 검사한다.
// 틱 수량(3주)을 주문 수량(10주)보다 작게 해 주문마다 체결이 여러 번 나뉘어 들어오고, 그 사이에 취소가 끼어든다.
// 무작위 취소는 워커가 그 주문에 닿기 전에 대부분 끝나므로, 워커가 지금 채우고 있는 주문을 골라 취소하는 스레드를
// 체결이 끝날 때까지 함께 돌린다 — 같은 주문을 같은 순간에 건드리는 창을 넓힌다.
@DisplayName("단일 종목 체결·취소·만료 동시 발행 (Kafka 통합)")
class SingleStockCommandConcurrencyIntegrationTest extends KafkaIntegrationTestSupport {

    private static final BigDecimal PRICE = new BigDecimal("200");
    private static final BigDecimal MARKET_UPPER_LIMIT = new BigDecimal("300");
    private static final BigDecimal INITIAL_CASH = new BigDecimal("1000000");
    private static final int ACCOUNTS = 20;
    // 취소·만료(최대 200 + 150 + 50건)를 빼고도 주문이 틱보다 많이 남게 한다. 그래야 모든 틱이 빠짐없이 체결되어
    // "체결 합 == 틱 합"을 검사할 수 있다 — 같은 틱이 두 번 처리되면 장부는 서로 맞아도 이 합이 넘친다.
    private static final int LIMIT_ORDERS_PER_ACCOUNT = 50;
    private static final int MARKET_ORDERS = 50;
    private static final int ORDER_QUANTITY = 10;
    private static final int FILL_THREADS = 8;
    private static final int TICKS_PER_FILL_THREAD = 125;
    private static final int TICK_QUANTITY = 3;
    private static final int CANCEL_THREADS = 6;
    private static final int CANCEL_TARGETS = 200;
    private static final int DOUBLE_CANCELS = 100;
    private static final int EXPIRE_THREADS = 2;
    private static final int REWINDS = 3;
    private static final int HOT_CANCEL_LIMIT = 150;
    private static final int HOT_CANCEL_BATCH = 4;
    private static final Duration DRAIN_WAIT = Duration.ofSeconds(120);
    private static final String FILL_LISTENER_ID = "matching-fill";

    @Autowired private FillCommandPublisher fillCommandPublisher;
    @Autowired private OrderCommandPublisher orderCommandPublisher;
    @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderHoldRepository orderHoldRepository;

    @Test
    @DisplayName("체결·취소·만료가 한 줄에 섞여도 주문 수량·홀딩·현금·보유 수량이 장부와 맞는다")
    void concurrentCommands_singleStock_ledgerConsistent() throws Exception {
        long seed = System.nanoTime();
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        FillDispatchResult.Queued probe = publishNoMatchTick(fixture.stockCode());

        Published published = publishConcurrently(fixture, new Random(seed), () -> { });
        cancelWhileFilling(fixture, probe.partition(), published);

        drain(fixture, probe.partition());
        assertThat(violations(fixture, published)).as("seed=%d", seed).isEmpty();
    }

    @Test
    @DisplayName("처리 중 워커를 멈추고 커밋된 위치를 되감아 재전달시켜도 장부가 맞는다 — 워터마크가 다시 온 명령을 거른다")
    void redeliveredMidFlight_ledgerStillConsistent() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        Fixture fixture = transactionTemplate.execute(status -> createFixture());
        FillDispatchResult.Queued probe = publishNoMatchTick(fixture.stockCode());
        double duplicatesBefore = fillDuplicates();

        AtomicInteger rewound = new AtomicInteger();
        Published published = publishConcurrently(fixture, random, () -> {
            for (int i = 0; i < REWINDS; i++) {
                sleepQuietly(300 + random.nextInt(700));
                rewound.addAndGet(rewindCommittedOffset(probe.partition(), probe.offset(), 30 + random.nextInt(120)));
            }
        });
        cancelWhileFilling(fixture, probe.partition(), published);

        drain(fixture, probe.partition());
        assertThat(rewound.get()).as("seed=%d 되감은 위치 수", seed).isPositive();
        assertThat(fillDuplicates() - duplicatesBefore).as("seed=%d 재전달을 워터마크가 거른 수", seed).isPositive();
        assertThat(violations(fixture, published)).as("seed=%d", seed).isEmpty();
    }

    // ===== 발행 =====

    // endRequested 는 취소·만료를 보낸 주문. 처리 중 취소가 계속 더해지므로 동시 집합이다.
    private record Published(int ticks, AtomicLong lastFillOffset, Set<Long> endRequested) {
    }

    // 모든 발행 스레드를 같은 순간에 풀고, 그동안 sideTask 를 함께 돌린다.
    private Published publishConcurrently(Fixture fixture, Random random, Runnable sideTask) throws Exception {
        List<Long> limitOrders = new ArrayList<>(fixture.limitOrderIds());
        Collections.shuffle(limitOrders, random);
        List<Long> cancelTargets = limitOrders.subList(0, CANCEL_TARGETS);
        List<Long> cancels = new ArrayList<>(cancelTargets);
        cancels.addAll(cancelTargets.subList(0, DOUBLE_CANCELS));
        Collections.shuffle(cancels, random);

        int threads = FILL_THREADS + CANCEL_THREADS + EXPIRE_THREADS + 1;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failedSends = new AtomicInteger();
        AtomicLong lastFillOffset = new AtomicLong(-1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        String stockCode = fixture.stockCode();

        try {
            for (int t = 0; t < FILL_THREADS; t++) {
                submit(pool, ready, start, errors, () -> {
                    for (int i = 0; i < TICKS_PER_FILL_THREAD; i++) {
                        if (fillCommandPublisher.publish(stockCode, sellTick(TICK_QUANTITY))
                                instanceof FillDispatchResult.Queued queued) {
                            lastFillOffset.accumulateAndGet(queued.offset(), Math::max);
                        } else {
                            failedSends.incrementAndGet();
                        }
                    }
                });
            }
            for (List<Long> slice : slices(cancels, CANCEL_THREADS)) {
                submit(pool, ready, start, errors, () -> slice.forEach(orderId -> {
                    if (!orderCommandPublisher.cancel(stockCode, orderId)) {
                        failedSends.incrementAndGet();
                    }
                }));
            }
            for (List<Long> slice : slices(fixture.marketOrderIds(), EXPIRE_THREADS)) {
                submit(pool, ready, start, errors, () -> slice.forEach(orderId -> {
                    if (!orderCommandPublisher.expire(stockCode, orderId)) {
                        failedSends.incrementAndGet();
                    }
                }));
            }
            submit(pool, ready, start, errors, sideTask);

            ready.await();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(DRAIN_WAIT.toSeconds(), TimeUnit.SECONDS)).as("발행 스레드 종료").isTrue();
        } finally {
            pool.shutdownNow();
            startFillListenerIfStopped();
        }

        assertThat(errors).isEmpty();
        assertThat(failedSends.get()).as("발행 실패").isZero();
        Set<Long> endRequested = ConcurrentHashMap.newKeySet();
        endRequested.addAll(cancelTargets);
        endRequested.addAll(fixture.marketOrderIds());
        return new Published(FILL_THREADS * TICKS_PER_FILL_THREAD, lastFillOffset, endRequested);
    }

    // 워커가 마지막 틱까지 처리할 때까지, 지금 채우는 중인 주문(PARTIALLY_FILLED)과 다음 차례 주문을 골라 취소한다.
    // 실제 사용자가 부분 체결된 주문을 취소하는 경우와 같다. 같은 주문을 체결 명령과 취소 명령이 앞뒤로 다툰다.
    private void cancelWhileFilling(Fixture fixture, int partition, Published published) {
        int sent = 0;
        while (sent < HOT_CANCEL_LIMIT && watermarkOf(partition) < published.lastFillOffset().get()) {
            List<Long> hot = jdbcTemplate.queryForList("""
                    SELECT order_id FROM trade_order
                    WHERE stock_code = ? AND status IN ('PARTIALLY_FILLED', 'PENDING')
                    ORDER BY (status = 'PARTIALLY_FILLED') DESC, price DESC, created_at, order_id
                    LIMIT ?
                    """, Long.class, fixture.stockCode(), HOT_CANCEL_BATCH);
            for (Long orderId : hot) {
                if (published.endRequested().add(orderId)) {
                    assertThat(orderCommandPublisher.cancel(fixture.stockCode(), orderId)).isTrue();
                    sent++;
                }
            }
            sleepQuietly(10);
        }
    }

    private void submit(ExecutorService pool, CountDownLatch ready, CountDownLatch start,
                        List<Throwable> errors, Runnable work) {
        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                work.run();
            } catch (Throwable e) {
                errors.add(e);
            }
        });
    }

    // 매수 틱은 매도 주문만 찾는다. 이 종목에 매도 주문이 없으므로 아무것도 체결하지 않고 위치만 남긴다.
    private FillDispatchResult.Queued publishNoMatchTick(String stockCode) {
        FillDispatchResult result = fillCommandPublisher.publish(stockCode, new LimitOrderFillEvent(
                UUID.randomUUID().toString(), OrderMethod.BUY, PRICE, 1, System.currentTimeMillis()));
        assertThat(result).isInstanceOf(FillDispatchResult.Queued.class);
        return (FillDispatchResult.Queued) result;
    }

    private LimitOrderFillEvent sellTick(int quantity) {
        return new LimitOrderFillEvent(UUID.randomUUID().toString(), OrderMethod.SELL, PRICE, quantity, System.currentTimeMillis());
    }

    // ===== 재전달 주입 =====

    // 워커를 멈추고 그 파티션의 커밋된 위치를 뒤로 돌린 뒤 다시 띄운다. 처리 결과는 DB 에 커밋됐는데 offset 커밋 전에
    // 죽은 상황과 같다 — 다시 띄우면 이미 처리한 명령이 다시 온다. 이번 테스트가 넣은 위치(floor) 아래로는 되감지 않는다.
    private int rewindCommittedOffset(int partition, long floor, int back) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(FILL_LISTENER_ID);
        container.stop();
        await().atMost(WAIT).until(() -> !container.isRunning());
        TopicPartition topicPartition = new TopicPartition(MatchingTopics.COMMANDS, partition);
        AtomicInteger rewound = new AtomicInteger();
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // 컨슈머가 그룹을 완전히 떠나기 전에는 위치를 바꿀 수 없다(GroupNotEmpty). 떠날 때까지 다시 시도한다.
            await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).ignoreExceptions().until(() -> {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(FILL_LISTENER_ID)
                        .partitionsToOffsetAndMetadata().get().get(topicPartition);
                if (committed == null || committed.offset() <= floor) {
                    return true;
                }
                long target = Math.max(floor, committed.offset() - back);
                admin.alterConsumerGroupOffsets(FILL_LISTENER_ID, Map.of(topicPartition, new OffsetAndMetadata(target)))
                        .all().get();
                rewound.set((int) (committed.offset() - target));
                return true;
            });
        } finally {
            container.start();
        }
        return rewound.get();
    }

    private void startFillListenerIfStopped() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(FILL_LISTENER_ID);
        if (!container.isRunning()) {
            container.start();
        }
    }

    private double fillDuplicates() {
        return meterRegistry.get("matching.fill.duplicate").counter().count();
    }

    // ===== 다 처리될 때까지 =====

    // 맨 끝에 표식 틱을 붙여 그 위치까지 워커가 기록하면 앞의 명령이 모두 처리된 것이다(한 파티션은 차례로 처리된다).
    // 그다음 체결마다 정산이 하나씩 생길 때까지 기다린다.
    private void drain(Fixture fixture, int partition) {
        FillDispatchResult.Queued marker = publishNoMatchTick(fixture.stockCode());
        assertThat(marker.partition()).isEqualTo(partition);
        await().atMost(DRAIN_WAIT).pollInterval(Duration.ofMillis(200))
                .until(() -> watermarkOf(partition) >= marker.offset());
        await().atMost(DRAIN_WAIT).pollInterval(Duration.ofMillis(200))
                .until(() -> unsettledExecutions(fixture.stockCode()) == 0);
    }

    private long watermarkOf(int partition) {
        List<Long> offsets = jdbcTemplate.queryForList(
                "SELECT last_offset FROM consumer_watermark WHERE topic = ? AND partition_no = ?",
                Long.class, MatchingTopics.COMMANDS, partition);
        return offsets.isEmpty() ? -1L : offsets.get(0);
    }

    private long unsettledExecutions(String stockCode) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM execution e
                WHERE e.stock_code = ?
                  AND NOT EXISTS (SELECT 1 FROM settlement s WHERE s.execution_id = e.execution_id)
                """, Long.class, stockCode);
    }

    // ===== 불변식 =====

    private record OrderRow(long orderId, long accountId, String type, String status, int quantity, int filled,
                            int executedQuantity, BigDecimal holdAmount) {
    }

    private List<String> violations(Fixture fixture, Published published) {
        List<String> violations = new ArrayList<>();
        List<OrderRow> orders = jdbcTemplate.query("""
                SELECT o.order_id, o.account_id, o.order_type, o.status, o.quantity, o.filled_quantity,
                       COALESCE((SELECT sum(e.quantity) FROM execution e WHERE e.order_id = o.order_id), 0) AS executed,
                       h.hold_amount
                FROM trade_order o
                LEFT JOIN order_hold h ON h.order_id = o.order_id
                WHERE o.stock_code = ?
                """, (rs, i) -> new OrderRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getBigDecimal(8)), fixture.stockCode());
        Set<Long> endRequested = published.endRequested();

        int totalExecuted = 0;
        for (OrderRow o : orders) {
            totalExecuted += o.executedQuantity();
            checkOrder(o, endRequested, fixture, violations);
        }
        if (totalExecuted != published.ticks() * TICK_QUANTITY) {
            violations.add("체결 합 " + totalExecuted + " != 틱 합 " + published.ticks() * TICK_QUANTITY
                    + " (넘치면 같은 틱이 두 번 처리됨, 모자라면 틱이 빠짐)");
        }
        checkAccounts(fixture, orders, violations);
        checkSettlements(fixture.stockCode(), violations);
        return violations;
    }

    private void checkOrder(OrderRow o, Set<Long> endRequested, Fixture fixture, List<String> violations) {
        String id = "주문 " + o.orderId() + "(" + o.type() + ", " + o.status() + ")";
        if (o.filled() != o.executedQuantity()) {
            violations.add(id + ": filled " + o.filled() + " != 체결 합 " + o.executedQuantity());
        }
        if (o.filled() > o.quantity()) {
            violations.add(id + ": 초과 체결 " + o.filled() + " > " + o.quantity());
        }
        boolean consistent = switch (o.status()) {
            case "FILLED" -> o.filled() == o.quantity();
            case "PARTIALLY_FILLED" -> o.filled() > 0 && o.filled() < o.quantity() && !endRequested.contains(o.orderId());
            case "PENDING" -> o.filled() == 0 && !endRequested.contains(o.orderId());
            case "CANCELLED" -> o.filled() < o.quantity() && endRequested.contains(o.orderId());
            default -> false;
        };
        if (!consistent) {
            violations.add(id + ": 상태와 수량이 맞지 않음 filled=" + o.filled() + " 취소·만료 요청=" + endRequested.contains(o.orderId()));
        }

        BigDecimal initialHold = "MARKET".equals(o.type())
                ? MARKET_UPPER_LIMIT.multiply(BigDecimal.valueOf(o.quantity()))
                : PRICE.multiply(BigDecimal.valueOf(o.quantity()));
        boolean ended = "FILLED".equals(o.status()) || "CANCELLED".equals(o.status());
        BigDecimal expectedHold = ended ? BigDecimal.ZERO : initialHold.subtract(PRICE.multiply(BigDecimal.valueOf(o.filled())));
        if (o.holdAmount() == null || o.holdAmount().compareTo(expectedHold) != 0) {
            violations.add(id + ": 주문 홀딩 " + o.holdAmount() + " != 기대 " + expectedHold);
        }
    }

    private void checkAccounts(Fixture fixture, List<OrderRow> orders, List<String> violations) {
        Map<Long, BigDecimal> holdByAccount = new HashMap<>();
        Map<Long, Integer> executedByAccount = new HashMap<>();
        for (OrderRow o : orders) {
            holdByAccount.merge(o.accountId(), o.holdAmount() == null ? BigDecimal.ZERO : o.holdAmount(), BigDecimal::add);
            executedByAccount.merge(o.accountId(), o.executedQuantity(), Integer::sum);
        }
        for (Long accountId : fixture.accountIds()) {
            Map<String, Object> row = jdbcTemplate.queryForMap("""
                    SELECT a.cash, a.hold_amount,
                           COALESCE((SELECT s.quantity FROM account_stock s
                                     WHERE s.account_id = a.account_id AND s.stock_code = ?), 0) AS held
                    FROM account a WHERE a.account_id = ?
                    """, fixture.stockCode(), accountId);
            int executed = executedByAccount.getOrDefault(accountId, 0);
            BigDecimal expectedCash = INITIAL_CASH.subtract(PRICE.multiply(BigDecimal.valueOf(executed)));
            String id = "계좌 " + accountId;
            if (((BigDecimal) row.get("cash")).compareTo(expectedCash) != 0) {
                violations.add(id + ": 현금 " + row.get("cash") + " != 기대 " + expectedCash);
            }
            BigDecimal expectedHold = holdByAccount.getOrDefault(accountId, BigDecimal.ZERO);
            if (((BigDecimal) row.get("hold_amount")).compareTo(expectedHold) != 0) {
                violations.add(id + ": 계좌 홀딩 " + row.get("hold_amount") + " != 주문 홀딩 합 " + expectedHold);
            }
            if (((Number) row.get("held")).intValue() != executed) {
                violations.add(id + ": 보유 수량 " + row.get("held") + " != 체결 수량 " + executed);
            }
        }
    }

    private void checkSettlements(String stockCode, List<String> violations) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                SELECT (SELECT count(*) FROM execution e WHERE e.stock_code = ?) AS executions,
                       (SELECT count(*) FROM settlement s
                        JOIN execution e ON e.execution_id = s.execution_id WHERE e.stock_code = ?) AS settlements
                """, stockCode, stockCode);
        if (((Number) row.get("executions")).longValue() != ((Number) row.get("settlements")).longValue()) {
            violations.add("정산 수 " + row.get("settlements") + " != 체결 수 " + row.get("executions"));
        }
    }

    // ===== 준비 =====

    private record Fixture(String stockCode, List<Long> accountIds, List<Long> limitOrderIds, List<Long> marketOrderIds) {
    }

    // 주문은 DB 에 바로 만들고 홀딩을 맞춘다(주문 접수와 같은 결과). 계좌는 기본 계좌가 아니라 미션 보상이 현금에 섞이지 않는다.
    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("동시성 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("C" + suffix).name("동시성종목 " + suffix).marketType("KOSPI").build());

        List<Long> accountIds = new ArrayList<>();
        List<Long> limitOrderIds = new ArrayList<>();
        List<Long> marketOrderIds = new ArrayList<>();
        for (int a = 0; a < ACCOUNTS; a++) {
            Member member = memberRepository.save(Member.builder()
                    .name("동시성 " + suffix + "-" + a)
                    .email("concurrency-" + suffix + "-" + a + "@test.com")
                    .provider(AuthProvider.LOCAL)
                    .build());
            Account account = accountRepository.save(Account.builder()
                    .member(member).contest(contest)
                    .accountName("동시성 계좌 " + a)
                    .cash(INITIAL_CASH)
                    .holdAmount(BigDecimal.ZERO)
                    .isDefault(false)
                    .build());
            accountIds.add(account.getAccountId());

            for (int i = 0; i < LIMIT_ORDERS_PER_ACCOUNT; i++) {
                limitOrderIds.add(saveBuyOrder(account, Order.createLimitOrder(account, stock, PRICE, ORDER_QUANTITY, OrderMethod.BUY),
                        PRICE.multiply(BigDecimal.valueOf(ORDER_QUANTITY))));
            }
        }
        for (int m = 0; m < MARKET_ORDERS; m++) {
            Account account = accountRepository.findById(accountIds.get(m % ACCOUNTS)).orElseThrow();
            marketOrderIds.add(saveBuyOrder(account, Order.createMarketOrder(account, stock, ORDER_QUANTITY, OrderMethod.BUY),
                    MARKET_UPPER_LIMIT.multiply(BigDecimal.valueOf(ORDER_QUANTITY))));
        }
        return new Fixture(stock.getCode(), accountIds, limitOrderIds, marketOrderIds);
    }

    private Long saveBuyOrder(Account account, Order order, BigDecimal holdAmount) {
        Order saved = orderRepository.save(order);
        account.increaseHoldAmount(holdAmount);
        orderHoldRepository.save(OrderHold.create(saved, account, holdAmount));
        return saved.getOrderId();
    }

    private static <T> List<List<T>> slices(List<T> items, int count) {
        List<List<T>> slices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            slices.add(new ArrayList<>());
        }
        for (int i = 0; i < items.size(); i++) {
            slices.get(i % count).add(items.get(i));
        }
        return slices;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
