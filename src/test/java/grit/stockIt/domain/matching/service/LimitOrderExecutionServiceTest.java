package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.service.ExecutionService;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.springframework.data.redis.core.ListOperations;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LimitOrderExecutionService 테스트")
class LimitOrderExecutionServiceTest {
    
    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ExecutionService executionService;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RedisOrderBookRepository redisOrderBookRepository;

    @Mock
    private OrderSubscriptionCoordinator orderSubscriptionCoordinator;

    @Mock
    private OrderHoldRepository orderHoldRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountStockRepository accountStockRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private LimitOrderExecutionService limitOrderExecutionService;

    private Member testMember;
    private Contest testContest;
    private Account testAccount;
    private Stock testStock;
    private Order testBuyOrder;
    private Order testSellOrder;
    private OrderHold testOrderHold;
    private AccountStock testAccountStock;

    @BeforeEach
    void setUp() {
        // fetchSize 설정
        ReflectionTestUtils.setField(limitOrderExecutionService, "fetchSize", 100);
        
        // ObjectMapper Mock 설정 (잔여 이벤트 재큐잉용)
        try {
            doAnswer(invocation -> {
                // 간단한 JSON 직렬화 (테스트용)
                return "{\"eventId\":\"test\",\"orderMethod\":\"SELL\",\"price\":100,\"quantity\":10}";
            }).when(objectMapper).writeValueAsString(any(grit.stockIt.domain.matching.dto.LimitOrderFillEvent.class));
        } catch (Exception e) {
            // Mockito가 예외를 처리하므로 무시
        }
        
        // StringRedisTemplate Mock 설정
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        // 테스트 데이터 생성
        testMember = Member.builder()
                .memberId(1L)
                .name("테스트 사용자")
                .email("test@test.com")
                .provider(grit.stockIt.domain.member.entity.AuthProvider.LOCAL)
                .build();

        testContest = Contest.builder()
                .contestId(1L)
                .contestName("테스트 대회")
                .build();

        testAccount = Account.builder()
                .accountId(1L)
                .member(testMember)
                .contest(testContest)
                .cash(new BigDecimal("10000"))
                .holdAmount(new BigDecimal("1000")) // 주문 생성 시 홀딩된 금액 (100원 * 10주)
                .build();

        testStock = Stock.builder()
                .code("005930")
                .name("삼성전자")
                .build();

        testBuyOrder = Order.createLimitOrder(
                testAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY
        );
        ReflectionTestUtils.setField(testBuyOrder, "orderId", 1L);

        testSellOrder = Order.createLimitOrder(
                testAccount, testStock, new BigDecimal("100"), 10, OrderMethod.SELL
        );
        ReflectionTestUtils.setField(testSellOrder, "orderId", 2L);

        // OrderHold의 홀딩 금액은 주문 금액과 동일하게 설정 (100원 * 10주 = 1000원)
        testOrderHold = OrderHold.create(testBuyOrder, testAccount, new BigDecimal("1000"));
        ReflectionTestUtils.setField(testOrderHold, "orderId", 1L);

        // AccountStock의 홀딩 수량은 주문 수량과 동일하게 설정 (매도 주문 시 사용)
        testAccountStock = AccountStock.create(testAccount, testStock, 10, new BigDecimal("90"));
        ReflectionTestUtils.setField(testAccountStock, "accountStockId", 1L);
        // 매도 주문을 위한 홀딩 수량 설정
        testAccountStock.increaseHoldQuantity(10);
    }

    @Test
    @DisplayName("지정가 매수 주문과 매도 체결 이벤트가 정상적으로 매칭되어 체결된다")
    void distributeEvent_BuyOrderMatchedWithSellEvent_Success() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(testBuyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));
        when(executionService.record(eq(testBuyOrder), eq(event.price()), eq(10)))
                .thenReturn(createExecution(testBuyOrder, event.price(), 10));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(testOrderHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        assertThat(testBuyOrder.getFilledQuantity()).isEqualTo(10);
        assertThat(testBuyOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(testAccount.getCash()).isEqualByComparingTo(new BigDecimal("9000")); // 10000 - 1000

        // ExecutionFilledEvent 발행 확인
        ArgumentCaptor<ExecutionFilledEvent> eventCaptor = ArgumentCaptor.forClass(ExecutionFilledEvent.class);
        verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

        // Redis 업데이트 확인
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stockCode);
    }

    @Test
    @DisplayName("지정가 매도 주문과 매수 체결 이벤트가 정상적으로 매칭되어 체결된다")
    void distributeEvent_SellOrderMatchedWithBuyEvent_Success() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.BUY,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                2L, stockCode, OrderMethod.SELL, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.BUY, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(testSellOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));
        when(executionService.record(eq(testSellOrder), eq(event.price()), eq(10)))
                .thenReturn(createExecution(testSellOrder, event.price(), 10));
        when(accountStockRepository.findByAccountAndStock(testAccount, testStock))
                .thenReturn(Optional.of(testAccountStock));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        assertThat(testSellOrder.getFilledQuantity()).isEqualTo(10);
        assertThat(testSellOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(testAccount.getCash()).isEqualByComparingTo(new BigDecimal("11000")); // 10000 + 1000
        assertThat(testAccountStock.getQuantity()).isEqualTo(0); // 10 - 10

        verify(redisOrderBookRepository, times(1)).removeOrder(2L, stockCode, OrderMethod.SELL);
    }

    @Test
    @DisplayName("부분 체결이 정상적으로 처리된다")
    void distributeEvent_PartialFill_Success() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                5, // 5주만 체결
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        // 부분 체결을 위한 OrderHold (홀딩 금액은 전체 주문 금액)
        OrderHold partialOrderHold = OrderHold.create(testBuyOrder, testAccount, new BigDecimal("1000"));
        ReflectionTestUtils.setField(partialOrderHold, "orderId", 1L);
        
        // Account의 holdAmount는 전체 주문 금액 (주문 생성 시 홀딩됨)
        Account partialAccount = Account.builder()
                .accountId(1L)
                .member(testMember)
                .contest(testContest)
                .cash(new BigDecimal("10000"))
                .holdAmount(new BigDecimal("1000")) // 전체 주문 금액
                .build();

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(testBuyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(partialAccount));
        when(executionService.record(eq(testBuyOrder), eq(event.price()), eq(5)))
                .thenReturn(createExecution(testBuyOrder, event.price(), 5));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(partialOrderHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        assertThat(testBuyOrder.getFilledQuantity()).isEqualTo(5);
        assertThat(testBuyOrder.getRemainingQuantity()).isEqualTo(5);
        assertThat(testBuyOrder.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        // 부분 체결이므로 Redis에서 수량만 업데이트
        verify(redisOrderBookRepository, times(1))
                .updateRemainingQuantity(1L, stockCode, OrderMethod.BUY, 5);
        verify(redisOrderBookRepository, never()).removeOrder(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("여러 주문이 우선순위에 따라 순차적으로 체결된다")
    void distributeEvent_MultipleOrdersSortedByPriority_Success() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                15, // 15주 체결
                Instant.now().toEpochMilli()
        );

        // 매도 이벤트이므로 가격이 높은 주문이 먼저 체결됨 (100원, 99원 순서)
        OrderBookEntry entry1 = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("99"),
                10, 10, Instant.now().toEpochMilli() - 1000, 1L // 더 이른 시간
        );
        OrderBookEntry entry2 = new OrderBookEntry(
                2L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        // 여러 주문을 위한 Account (총 홀딩 금액: 990 + 1000 = 1990원)
        Account multiOrderAccount = Account.builder()
                .accountId(1L)
                .member(testMember)
                .contest(testContest)
                .cash(new BigDecimal("10000"))
                .holdAmount(new BigDecimal("1990")) // 두 주문의 총 홀딩 금액
                .build();

        Order order1 = Order.createLimitOrder(multiOrderAccount, testStock, new BigDecimal("99"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(order1, "orderId", 1L);
        Order order2 = Order.createLimitOrder(multiOrderAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(order2, "orderId", 2L);

        // 각 주문에 대한 OrderHold 생성
        OrderHold hold1 = OrderHold.create(order1, multiOrderAccount, new BigDecimal("990")); // 99 * 10
        ReflectionTestUtils.setField(hold1, "orderId", 1L);
        OrderHold hold2 = OrderHold.create(order2, multiOrderAccount, new BigDecimal("1000")); // 100 * 10
        ReflectionTestUtils.setField(hold2, "orderId", 2L);

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(entry1, entry2));
        when(orderRepository.findAllById(anyList()))
                .thenAnswer(invocation -> {
                    List<Long> orderIds = invocation.getArgument(0);
                    List<Order> result = new java.util.ArrayList<>();
                    for (Long orderId : orderIds) {
                        if (orderId == 1L) {
                            result.add(order1);
                        } else if (orderId == 2L) {
                            result.add(order2);
                        }
                    }
                    return result;
                });
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(multiOrderAccount));
        when(executionService.record(any(Order.class), any(BigDecimal.class), anyInt()))
                .thenAnswer(invocation -> {
                    Order order = invocation.getArgument(0);
                    BigDecimal price = invocation.getArgument(1);
                    Integer quantity = invocation.getArgument(2);
                    return createExecution(order, price, quantity);
                });
        when(orderHoldRepository.findById(anyLong()))
                .thenAnswer(invocation -> {
                    Long orderId = invocation.getArgument(0);
                    if (orderId == 1L) {
                        return Optional.of(hold1);
                    } else if (orderId == 2L) {
                        return Optional.of(hold2);
                    }
                    return Optional.empty();
                });

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(2);
        // 매도 이벤트이므로 가격이 높은 주문(100원)이 먼저 체결되어야 함
        assertThat(order2.getFilledQuantity()).isEqualTo(10); // 100원 주문이 먼저 전량 체결
        assertThat(order1.getFilledQuantity()).isEqualTo(5); // 99원 주문이 5주 체결 (15 - 10 = 5)
    }

    @Test
    @DisplayName("매칭 가능한 주문이 없으면 빈 리스트를 반환한다")
    void distributeEvent_NoMatchingOrders_ReturnsEmpty() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of());

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        verify(orderRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("현금 부족으로 부분 체결되고 주문이 취소된다")
    void distributeEvent_InsufficientCash_CancelsOrder() {
        // Given
        String stockCode = "005930";
        Account poorAccount = Account.builder()
                .accountId(1L)
                .member(testMember)
                .contest(testContest)
                .cash(new BigDecimal("50")) // 100원 필요하지만 50원만 있음 (1주도 살 수 없음)
                .holdAmount(new BigDecimal("1000")) // 주문 생성 시 홀딩된 금액
                .build();

        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        Order buyOrder = Order.createLimitOrder(poorAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(buyOrder, "orderId", 1L);

        OrderHold orderHold = OrderHold.create(buyOrder, poorAccount, new BigDecimal("1000"));
        ReflectionTestUtils.setField(orderHold, "orderId", 1L);

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(buyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(poorAccount));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(orderHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        assertThat(buyOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stockCode);
    }

    @Test
    @DisplayName("현금 부족으로 부분 체결된다")
    void distributeEvent_InsufficientCash_PartialFill() {
        // Given
        String stockCode = "005930";
        Account account = Account.builder()
                .accountId(1L)
                .member(testMember)
                .contest(testContest)
                .cash(new BigDecimal("700")) // 1000원 필요하지만 700원만 있음 (7주만 가능)
                .holdAmount(new BigDecimal("1000")) // 주문 생성 시 홀딩된 금액
                .build();

        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        Order buyOrder = Order.createLimitOrder(account, testStock, new BigDecimal("100"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(buyOrder, "orderId", 1L);

        OrderHold orderHold = OrderHold.create(buyOrder, account, new BigDecimal("1000"));
        ReflectionTestUtils.setField(orderHold, "orderId", 1L);

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(buyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(account));
        when(executionService.record(eq(buyOrder), eq(event.price()), eq(7)))
                .thenReturn(createExecution(buyOrder, event.price(), 7));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(orderHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        assertThat(buyOrder.getFilledQuantity()).isEqualTo(7);
        assertThat(buyOrder.getRemainingQuantity()).isEqualTo(3);
        assertThat(account.getCash()).isEqualByComparingTo(BigDecimal.ZERO); // 700 - 700 = 0
    }

    @Test
    @DisplayName("이미 취소된 주문은 체결되지 않는다")
    void distributeEvent_CancelledOrder_Skipped() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        testBuyOrder.markCancelled();

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(testBuyOrder));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(executionService, never()).record(any(), any(), anyInt());
    }

    @Test
    @DisplayName("DB에 주문이 없으면 Redis에서 제거하고 스킵한다")
    void distributeEvent_OrderNotFoundInDB_RemovesFromRedis() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                10,
                Instant.now().toEpochMilli()
        );

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of()); // DB에 주문 없음

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stockCode);
    }

    @Test
    @DisplayName("체결 이벤트 수량이 0 이하면 빈 리스트를 반환한다")
    void distributeEvent_ZeroQuantity_ReturnsEmpty() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                0, // 수량 0
                Instant.now().toEpochMilli()
        );

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        verify(redisOrderBookRepository, never()).fetchMatchingEntries(any(), any(), any(), anyInt());
    }

    // Helper 메서드
    private Execution createExecution(Order order, BigDecimal price, int quantity) {
        Execution execution = Execution.of(order, price, quantity);
        // executionId는 Long 타입이므로 Long으로 설정
        long executionId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        ReflectionTestUtils.setField(execution, "executionId", executionId);
        return execution;
    }
}

