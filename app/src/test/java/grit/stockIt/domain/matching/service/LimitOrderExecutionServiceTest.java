package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.entity.ExchangeFill;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.entity.ExchangeOrderStatus;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.queue.ExecutionReportOutbox;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkWriter;
import grit.stockIt.domain.matching.repository.ExchangeFillRepository;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LimitOrderExecutionService — 거래소 오더북 체결")
class LimitOrderExecutionServiceTest {

    private static final String STOCK = "005930";
    private static final long ACCOUNT_ID = 1L;
    private static final CommandPosition POSITION = new CommandPosition("matching.commands", 0, 1L);
    private static final BigDecimal MARKET_BUY_PRICE = new BigDecimal("999999999");

    @Mock private ExchangeOrderRepository exchangeOrderRepository;
    @Mock private ExchangeFillRepository exchangeFillRepository;
    @Mock private OrderBookRepository orderBookRepository;
    @Mock private ConsumerWatermarkWriter consumerWatermarkWriter;
    @Mock private ExecutionReportOutbox executionReportOutbox;
    @Mock private FillIdGenerator fillIdGenerator;

    @InjectMocks
    private LimitOrderExecutionService limitOrderExecutionService;

    private final Map<Long, ExchangeOrder> book = new HashMap<>();
    private final AtomicLong nextFillId = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(limitOrderExecutionService, "fetchSize", 100);
        when(consumerWatermarkWriter.advance(any())).thenReturn(true);
        when(exchangeOrderRepository.findAllById(any())).thenAnswer(invocation -> {
            List<ExchangeOrder> found = new ArrayList<>();
            for (Long id : invocation.<Iterable<Long>>getArgument(0)) {
                if (book.containsKey(id)) {
                    found.add(book.get(id));
                }
            }
            return found;
        });
        when(fillIdGenerator.next(anyInt())).thenAnswer(invocation -> nextFillId.getAndIncrement());
        when(exchangeFillRepository.save(any(ExchangeFill.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("지정가 매수 주문이 매도 틱에 전량 체결되고, 남은 수량 0 을 통보에 싣는다")
    void buyOrder_fullyFilledBySellTick() {
        ExchangeOrder buy = bookOrder(1L, OrderMethod.BUY, OrderType.LIMIT, "100", 10);
        stubCandidates(OrderMethod.SELL, "100", entry(buy));

        List<FilledExecution> executions = fill(tick(OrderMethod.SELL, "100", 10));

        assertThat(executions).singleElement().satisfies(execution -> {
            assertThat(execution.executionId()).isEqualTo(100L);
            assertThat(execution.orderId()).isEqualTo(1L);
            assertThat(execution.accountId()).isEqualTo(ACCOUNT_ID);
            assertThat(execution.quantity()).isEqualTo(10);
            assertThat(execution.cumQuantity()).isEqualTo(10);
            assertThat(execution.leavesQuantity()).isZero();
        });
        assertThat(buy.getFilledQuantity()).isEqualTo(10);
        assertThat(buy.getStatus()).isEqualTo(ExchangeOrderStatus.FILLED);
    }

    @Test
    @DisplayName("지정가 매도 주문이 매수 틱에 체결된다")
    void sellOrder_filledByBuyTick() {
        ExchangeOrder sell = bookOrder(2L, OrderMethod.SELL, OrderType.LIMIT, "100", 10);
        stubCandidates(OrderMethod.BUY, "100", entry(sell));

        List<FilledExecution> executions = fill(tick(OrderMethod.BUY, "100", 10));

        assertThat(executions).hasSize(1);
        assertThat(sell.getStatus()).isEqualTo(ExchangeOrderStatus.FILLED);
    }

    @Test
    @DisplayName("부분 체결이면 주문은 오더북에 남고 남은 수량을 통보에 싣는다")
    void partialFill_keepsOrderOpen() {
        ExchangeOrder buy = bookOrder(1L, OrderMethod.BUY, OrderType.LIMIT, "100", 10);
        stubCandidates(OrderMethod.SELL, "100", entry(buy));

        List<FilledExecution> executions = fill(tick(OrderMethod.SELL, "100", 5));

        assertThat(executions).singleElement().satisfies(execution -> {
            assertThat(execution.cumQuantity()).isEqualTo(5);
            assertThat(execution.leavesQuantity()).isEqualTo(5);
        });
        assertThat(buy.getStatus()).isEqualTo(ExchangeOrderStatus.OPEN);
        assertThat(buy.getRemainingQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("매도 틱이면 높은 가격의 매수 주문부터 채운다")
    void multipleOrders_filledByPricePriority() {
        ExchangeOrder low = bookOrder(1L, OrderMethod.BUY, OrderType.LIMIT, "99", 10);
        ExchangeOrder high = bookOrder(2L, OrderMethod.BUY, OrderType.LIMIT, "100", 10);
        stubCandidates(OrderMethod.SELL, "99", entry(low, -1000), entry(high, 0));

        List<FilledExecution> executions = fill(tick(OrderMethod.SELL, "99", 15));

        assertThat(executions).hasSize(2);
        assertThat(high.getFilledQuantity()).isEqualTo(10);
        assertThat(low.getFilledQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("시장가 매수 주문은 같은 틱에서 지정가보다 먼저 채운다")
    void marketOrder_hasPriorityOverLimit() {
        ExchangeOrder limit = bookOrder(1L, OrderMethod.BUY, OrderType.LIMIT, "100", 10);
        ExchangeOrder market = bookOrder(2L, OrderMethod.BUY, OrderType.MARKET, MARKET_BUY_PRICE.toPlainString(), 10);
        stubCandidates(OrderMethod.SELL, "100", entry(limit, -1000), entry(market, 0));

        fill(tick(OrderMethod.SELL, "100", 10));

        assertThat(market.getStatus()).isEqualTo(ExchangeOrderStatus.FILLED);
        assertThat(limit.getFilledQuantity()).isZero();
    }

    @Test
    @DisplayName("맞는 주문이 없으면 체결하지 않는다")
    void noCandidates_returnsEmpty() {
        stubCandidates(OrderMethod.SELL, "100");

        assertThat(fill(tick(OrderMethod.SELL, "100", 10))).isEmpty();
        verify(exchangeOrderRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("오더북 조회 뒤 이미 끝난 주문은 건너뛰고 구독을 하나 줄인다")
    void closedOrder_skipped() {
        ExchangeOrder buy = bookOrder(1L, OrderMethod.BUY, OrderType.LIMIT, "100", 10);
        OrderBookEntry stale = entry(buy);
        buy.cancel();
        stubCandidates(OrderMethod.SELL, "100", stale);

        assertThat(fill(tick(OrderMethod.SELL, "100", 10))).isEmpty();
        verify(exchangeFillRepository, never()).save(any());
    }

    @Test
    @DisplayName("오더북에 없는 주문은 건너뛴다")
    void missingOrder_skipped() {
        ExchangeOrder ghost = ExchangeOrder.accept(9L, ACCOUNT_ID, STOCK, OrderMethod.BUY, OrderType.LIMIT,
                new BigDecimal("100"), 10);
        stubCandidates(OrderMethod.SELL, "100", entry(ghost));

        assertThat(fill(tick(OrderMethod.SELL, "100", 10))).isEmpty();
        verify(exchangeFillRepository, never()).save(any());
    }

    @Test
    @DisplayName("수량이 0 인 틱은 오더북을 보지 않는다")
    void zeroQuantityTick_returnsEmpty() {
        assertThat(fill(tick(OrderMethod.SELL, "100", 0))).isEmpty();
        verify(orderBookRepository, never()).fetchMatchingEntries(any(), any(), any(), eq(100));
    }

    @Test
    @DisplayName("체결한 건은 같은 호출(트랜잭션) 안에서 아웃박스에 적고, 재전달된 위치는 적지 않는다")
    void fillOnce_appendsReportsToOutbox_onlyWhenApplied() {
        ExchangeOrder buy = bookOrder(1L, OrderMethod.BUY, OrderType.LIMIT, "100", 10);
        stubCandidates(OrderMethod.SELL, "100", entry(buy));
        LimitOrderFillEvent event = tick(OrderMethod.SELL, "100", 10);

        List<FilledExecution> executions = limitOrderExecutionService.fillOnce(STOCK, event, POSITION).orElseThrow();

        verify(executionReportOutbox).append(eq(executions), anyLong());

        when(consumerWatermarkWriter.advance(any())).thenReturn(false);
        assertThat(limitOrderExecutionService.fillOnce(STOCK, event, POSITION)).isEmpty();
        verify(executionReportOutbox, times(1)).append(any(), anyLong());
    }

    private ExchangeOrder bookOrder(Long orderId, OrderMethod method, OrderType type, String price, int quantity) {
        ExchangeOrder order = ExchangeOrder.accept(orderId, ACCOUNT_ID, STOCK, method, type, new BigDecimal(price), quantity);
        book.put(orderId, order);
        return order;
    }

    private void stubCandidates(OrderMethod takerMethod, String price, OrderBookEntry... entries) {
        when(orderBookRepository.fetchMatchingEntries(STOCK, takerMethod, new BigDecimal(price), 100))
                .thenReturn(List.of(entries));
    }

    private static OrderBookEntry entry(ExchangeOrder order) {
        return entry(order, 0);
    }

    private static OrderBookEntry entry(ExchangeOrder order, long acceptedOffsetMillis) {
        return new OrderBookEntry(order.getOrderId(), order.getStockCode(), order.getOrderMethod(), order.getPrice(),
                order.getRemainingQuantity(), order.getQuantity(), Instant.now().toEpochMilli() + acceptedOffsetMillis,
                order.getAccountId());
    }

    private static LimitOrderFillEvent tick(OrderMethod method, String price, int quantity) {
        return new LimitOrderFillEvent(UUID.randomUUID().toString(), method, new BigDecimal(price), quantity,
                Instant.now().toEpochMilli());
    }

    private List<FilledExecution> fill(LimitOrderFillEvent event) {
        return limitOrderExecutionService.fillOnce(STOCK, event, POSITION).orElseThrow();
    }
}
