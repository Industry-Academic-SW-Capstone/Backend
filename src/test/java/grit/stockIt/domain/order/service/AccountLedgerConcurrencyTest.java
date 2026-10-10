package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import grit.stockIt.domain.stock.dto.StockDetailResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

// 같은 계좌를 서로 다른 스레드가 동시에 고칠 때 현금·홀딩·보유 수량이 맞는지 본다. 계좌는 종목 파티션으로 보호받지 못한다 —
// 주문 접수(HTTP), 취소·만료(체결 워커), 정산(정산 워커)이 각자 다른 스레드에서 같은 계좌 행을 바꾼다.
// Account 는 행 전체를 UPDATE 하므로, 셋 중 하나라도 계좌를 잠그지 않으면 늦게 커밋한 쪽이 상대의 변경을 덮어쓴다.
// 큐 순서가 아니라 DB 잠금을 보는 테스트라 Kafka 없이 서비스를 바로 부른다.
@DisplayName("같은 계좌 동시 변경 — 주문 접수·워커 취소·정산 (통합 테스트)")
class AccountLedgerConcurrencyTest extends IntegrationTestSupport {

    private static final BigDecimal PRICE = new BigDecimal("200");
    private static final BigDecimal INITIAL_CASH = new BigDecimal("10000000");
    private static final int ORDER_QUANTITY = 10;
    private static final int INITIAL_ORDERS = 200;
    private static final int PREFILL_TICKS = 100;
    private static final int WORKER_STEPS = 300;
    private static final int TICK_QUANTITY = 3;
    private static final int SETTLEMENT_THREADS = 4;
    private static final int ORDER_THREADS = 2;
    private static final int ORDERS_PER_THREAD = 25;
    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired private OrderService orderService;
    @Autowired private OrderCancelService orderCancelService;
    @Autowired private ExecutionSettlementService executionSettlementService;
    @Autowired private LimitOrderFillCoordinator limitOrderFillCoordinator;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderHoldRepository orderHoldRepository;

    @MockitoBean private StockDetailService stockDetailService;

    // 워터마크는 테스트 사이에 지우지 않는다. 테스트마다 다른 토픽 이름을 써서 서로의 위치에 걸리지 않게 한다.
    private String topic;
    private final AtomicLong nextOffset = new AtomicLong();

    @BeforeEach
    void setUp() {
        topic = "matching.commands-" + UUID.randomUUID().toString().substring(0, 8);
        when(stockDetailService.getStockDetail(anyString())).thenReturn(Mono.just(tradeableStockDetail()));
    }

    @Test
    @DisplayName("정산이 계좌를 잠근 사이에 들어온 취소는 기다렸다가 정산이 바꾼 현금 위에서 홀딩을 푼다 — 정산의 변경이 사라지지 않는다")
    void cancelWaitsForSettlementLock_settlementChangeSurvives() throws Exception {
        Fixture fixture = transactionTemplate.execute(status -> createFixture(1));
        Long orderId = fixture.orderIds().get(0);
        BigDecimal settled = new BigDecimal("12345");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 정산 워커가 계좌를 잠그고 현금을 바꾸는 중인 상태를 흉내 낸다.
            CompletableFuture<Void> settlement = CompletableFuture.runAsync(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        Account account = accountRepository.findByIdWithLock(fixture.accountId()).orElseThrow();
                        account.decreaseCash(settled);
                        locked.countDown();
                        awaitQuietly(release);
                    }), pool);
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<QueuedCancelOutcome> cancel = CompletableFuture.supplyAsync(() ->
                    orderCancelService.cancelOnce(fixture.stockCode(), orderId, nextPosition()), pool);

            Thread.sleep(500);
            assertThat(cancel).as("계좌가 잠겨 있는 동안 취소는 끝나지 않는다").isNotDone();

            release.countDown();
            settlement.get(10, TimeUnit.SECONDS);
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo(QueuedCancelOutcome.CANCELLED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        Map<String, Object> account = accountRow(fixture.accountId());
        assertThat((BigDecimal) account.get("cash")).as("정산이 뺀 현금이 살아 있다")
                .isEqualByComparingTo(INITIAL_CASH.subtract(settled));
        assertThat((BigDecimal) account.get("hold_amount")).as("취소한 주문의 홀딩만큼 풀렸다")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("주문 접수·워커(체결·취소)·정산이 한 계좌를 동시에 고쳐도 현금·홀딩·보유 수량이 장부와 맞는다")
    void concurrentAccountWriters_ledgerConsistent() throws Exception {
        long seed = System.nanoTime();
        Random random = new Random(seed);
        Fixture fixture = transactionTemplate.execute(status -> createFixture(INITIAL_ORDERS));

        // 미리 체결만 해 두어 정산할 거리를 만든다. 정산 전이라 일부 주문은 "체결됐지만 미정산" 상태로 취소를 맞는다.
        BlockingQueue<Long> toSettle = new LinkedBlockingQueue<>();
        for (int i = 0; i < PREFILL_TICKS; i++) {
            fill(fixture.stockCode()).forEach(execution -> toSettle.add(execution.executionId()));
        }
        assertThat(toSettle).isNotEmpty();
        // 보유 종목 행은 첫 정산이 만든다. 미리 하나 정산해 두어, 경합이 행 생성 충돌이 아니라 계좌 행 갱신에서 나게 한다.
        executionSettlementService.settle(toSettle.poll());

        AtomicBoolean workerDone = new AtomicBoolean();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        int threads = 1 + SETTLEMENT_THREADS + ORDER_THREADS;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);

        // 체결 워커 — 한 종목의 체결과 취소는 운영에서 워커 하나가 차례로 처리하므로 한 스레드에서 섞는다.
        submit(pool, ready, start, errors, () -> {
            try {
                for (int i = 0; i < WORKER_STEPS; i++) {
                    if (random.nextInt(10) < 3) {
                        cancelSomeActiveOrder(fixture, random);
                    } else {
                        fill(fixture.stockCode()).forEach(execution -> toSettle.add(execution.executionId()));
                    }
                }
            } finally {
                workerDone.set(true);
            }
        });
        // 정산 워커 — 큐에서 꺼낸 체결을 정산한다. 체결 하나는 한 스레드만 꺼낸다.
        for (int t = 0; t < SETTLEMENT_THREADS; t++) {
            submit(pool, ready, start, errors, () -> {
                while (!(workerDone.get() && toSettle.isEmpty())) {
                    Long executionId = pollQuietly(toSettle);
                    if (executionId != null) {
                        executionSettlementService.settle(executionId);
                    }
                }
            });
        }
        // 주문 접수 — 같은 계좌로 새 매수 주문을 넣는다(계좌 잠금 → 가능 현금 확인 → 홀딩 증가).
        for (int t = 0; t < ORDER_THREADS; t++) {
            submit(pool, ready, start, errors, () -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(fixture.email(), null, List.of()));
                try {
                    for (int i = 0; i < ORDERS_PER_THREAD; i++) {
                        orderService.createLimitOrder(new LimitOrderCreateRequest(
                                fixture.accountId(), fixture.stockCode(), PRICE, ORDER_QUANTITY, OrderMethod.BUY));
                    }
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)).as("seed=%d 스레드 종료", seed).isTrue();
        assertThat(errors).as("seed=%d", seed).isEmpty();

        assertThat(violations(fixture)).as("seed=%d", seed).isEmpty();
    }

    // ===== 워커 동작 =====

    private List<FilledExecution> fill(String stockCode) {
        LimitOrderFillEvent tick = new LimitOrderFillEvent(
                UUID.randomUUID().toString(), OrderMethod.SELL, PRICE, TICK_QUANTITY, System.currentTimeMillis());
        return limitOrderFillCoordinator.processQueuedFill(stockCode, tick, nextPosition()).executions();
    }

    // 미정산 체결이 있는 부분 체결 주문을 절반쯤 고른다 — 취소가 "정산이 뺄 몫은 남기고 푼다"를 지키는지가 여기서 갈린다.
    private void cancelSomeActiveOrder(Fixture fixture, Random random) {
        String status = random.nextBoolean() ? "'PARTIALLY_FILLED'" : "'PENDING', 'PARTIALLY_FILLED'";
        List<Long> candidates = jdbcTemplate.queryForList(
                "SELECT order_id FROM trade_order WHERE account_id = ? AND status IN (" + status + ")",
                Long.class, fixture.accountId());
        if (!candidates.isEmpty()) {
            orderCancelService.cancelOnce(fixture.stockCode(), candidates.get(random.nextInt(candidates.size())), nextPosition());
        }
    }

    private CommandPosition nextPosition() {
        return new CommandPosition(topic, 0, nextOffset.getAndIncrement());
    }

    // ===== 불변식 =====

    private record OrderRow(long orderId, String status, int quantity, int filled, int executed, BigDecimal holdAmount) {
    }

    private List<String> violations(Fixture fixture) {
        List<String> violations = new ArrayList<>();
        List<OrderRow> orders = jdbcTemplate.query("""
                SELECT o.order_id, o.status, o.quantity, o.filled_quantity,
                       COALESCE((SELECT sum(e.quantity) FROM execution e WHERE e.order_id = o.order_id), 0),
                       h.hold_amount
                FROM trade_order o LEFT JOIN order_hold h ON h.order_id = o.order_id
                WHERE o.account_id = ?
                """, (rs, i) -> new OrderRow(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5), rs.getBigDecimal(6)), fixture.accountId());

        int executedTotal = 0;
        BigDecimal holdTotal = BigDecimal.ZERO;
        for (OrderRow o : orders) {
            executedTotal += o.executed();
            BigDecimal hold = o.holdAmount() == null ? BigDecimal.ZERO : o.holdAmount();
            holdTotal = holdTotal.add(hold);
            String id = "주문 " + o.orderId() + "(" + o.status() + ")";
            if (o.filled() != o.executed()) {
                violations.add(id + ": filled " + o.filled() + " != 체결 합 " + o.executed());
            }
            boolean ended = "FILLED".equals(o.status()) || "CANCELLED".equals(o.status());
            BigDecimal expected = ended ? BigDecimal.ZERO
                    : PRICE.multiply(BigDecimal.valueOf((long) o.quantity() - o.filled()));
            if (hold.compareTo(expected) != 0) {
                violations.add(id + ": 주문 홀딩 " + hold + " != 기대 " + expected + " (filled " + o.filled() + ")");
            }
        }

        Map<String, Object> account = accountRow(fixture.accountId());
        BigDecimal cash = (BigDecimal) account.get("cash");
        BigDecimal accountHold = (BigDecimal) account.get("hold_amount");
        BigDecimal expectedCash = INITIAL_CASH.subtract(PRICE.multiply(BigDecimal.valueOf(executedTotal)));
        if (cash.compareTo(expectedCash) != 0) {
            violations.add("현금 " + cash + " != 기대 " + expectedCash);
        }
        if (accountHold.compareTo(holdTotal) != 0) {
            violations.add("계좌 홀딩 " + accountHold + " != 주문 홀딩 합 " + holdTotal);
        }
        if (accountHold.compareTo(cash) > 0) {
            violations.add("홀딩 " + accountHold + " > 현금 " + cash);
        }
        Integer held = jdbcTemplate.queryForObject(
                "SELECT COALESCE(sum(quantity), 0) FROM account_stock WHERE account_id = ? AND stock_code = ?",
                Integer.class, fixture.accountId(), fixture.stockCode());
        if (held == null || held != executedTotal) {
            violations.add("보유 수량 " + held + " != 체결 수량 " + executedTotal);
        }
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT (SELECT count(*) FROM execution WHERE account_id = ?) AS executions,
                       (SELECT count(*) FROM settlement WHERE account_id = ?) AS settlements
                """, fixture.accountId(), fixture.accountId());
        if (((Number) counts.get("executions")).longValue() != ((Number) counts.get("settlements")).longValue()) {
            violations.add("정산 수 " + counts.get("settlements") + " != 체결 수 " + counts.get("executions"));
        }
        if (orders.size() != INITIAL_ORDERS + ORDER_THREADS * ORDERS_PER_THREAD) {
            violations.add("주문 수 " + orders.size() + " — 접수가 일부 빠졌다");
        }
        return violations;
    }

    private Map<String, Object> accountRow(Long accountId) {
        return jdbcTemplate.queryForMap("SELECT cash, hold_amount FROM account WHERE account_id = ?", accountId);
    }

    // ===== 준비 =====

    private record Fixture(String stockCode, Long accountId, String email, List<Long> orderIds) {
    }

    // 주문은 DB 에 바로 만들고 홀딩을 맞춘다(주문 접수와 같은 결과). 기본 계좌가 아니라 미션 보상이 현금에 섞이지 않는다.
    private Fixture createFixture(int orders) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "ledger-" + suffix + "@test.com";
        Member member = memberRepository.save(Member.builder()
                .name("장부 " + suffix).email(email).provider(AuthProvider.LOCAL).build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("장부 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Account account = accountRepository.save(Account.builder()
                .member(member).contest(contest)
                .accountName("장부 계좌 " + suffix)
                .cash(INITIAL_CASH)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("L" + suffix).name("장부종목 " + suffix).marketType("KOSPI").build());

        List<Long> orderIds = new ArrayList<>();
        BigDecimal holdAmount = PRICE.multiply(BigDecimal.valueOf(ORDER_QUANTITY));
        for (int i = 0; i < orders; i++) {
            Order order = orderRepository.save(Order.createLimitOrder(account, stock, PRICE, ORDER_QUANTITY, OrderMethod.BUY));
            account.increaseHoldAmount(holdAmount);
            orderHoldRepository.save(OrderHold.create(order, account, holdAmount));
            orderIds.add(order.getOrderId());
        }
        return new Fixture(stock.getCode(), account.getAccountId(), email, orderIds);
    }

    private StockDetailResponse tradeableStockDetail() {
        return new StockDetailResponse(
                "IGNORED", "무시됨", 0, 0, "0", null,
                0L, 0L, 0L, 0.0, 0.0, 0.0,
                0, 0, 0, 0, 0,
                null, null,
                true, null,
                null, null
        );
    }

    // ===== 스레드 =====

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

    private static Long pollQuietly(BlockingQueue<Long> queue) {
        try {
            return queue.poll(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
