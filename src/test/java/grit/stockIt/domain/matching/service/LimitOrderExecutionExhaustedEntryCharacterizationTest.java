package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.service.ExecutionService;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 특성화(Characterization) 테스트: 소진된(exhausted, remainingQuantity<=0) OrderBookEntry의
 * Redis 삭제 경로. distributeEvent() L252-256의 소진 항목 삭제 루프는 할당 루프의 break와
 * 무관하게 orderedEntries 전체를 순회한다. 이 gap은 기존 테스트에서 전혀 커버되지 않았다.
 * 현재 동작을 있는 그대로 기록하며 고치지 않는다 (특히 케이스 3의 누수 동작).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LimitOrderExecutionService 소진 엔트리 삭제 특성화 테스트")
class LimitOrderExecutionExhaustedEntryCharacterizationTest {

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

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private LimitOrderExecutionService limitOrderExecutionService;

    private Member testMember;
    private Contest testContest;
    private Account testAccount;
    private Stock testStock;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(limitOrderExecutionService, "fetchSize", 100);
        when(redisTemplate.opsForList()).thenReturn(listOperations);

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
                .cash(new BigDecimal("100000"))
                .holdAmount(new BigDecimal("10000"))
                .build();

        testStock = Stock.builder()
                .code("005930")
                .name("삼성전자")
                .build();
    }

    private LimitOrderFillEvent sellEvent(int quantity) {
        return new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                quantity,
                Instant.now().toEpochMilli()
        );
    }

    private Order limitBuy(long orderId, BigDecimal price, int quantity) {
        Order order = Order.createLimitOrder(testAccount, testStock, price, quantity, OrderMethod.BUY);
        ReflectionTestUtils.setField(order, "orderId", orderId);
        return order;
    }

    private OrderHold hold(Order order, BigDecimal amount, long orderId) {
        OrderHold h = OrderHold.create(order, testAccount, amount);
        ReflectionTestUtils.setField(h, "orderId", orderId);
        return h;
    }

    private Execution createExecution(Order order, BigDecimal price, int quantity) {
        Execution execution = Execution.of(order, price, quantity);
        long executionId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        ReflectionTestUtils.setField(execution, "executionId", executionId);
        return execution;
    }

    @Test
    @DisplayName("케이스1: 소진/비소진 후보가 섞여 있으면 소진된 orderId만 정확히 제거된다")
    void distributeEvent_MixedExhaustedAndActiveCandidates_RemovesOnlyExhausted() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = sellEvent(10);

        // 매도 이벤트이므로 가격 높은 순 우선. entry1(가격100, 소진), entry2(가격99, 활성)
        OrderBookEntry exhaustedEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                0, 10, Instant.now().toEpochMilli(), 1L // remainingQuantity=0 → isExhausted()=true
        );
        OrderBookEntry activeEntry = new OrderBookEntry(
                2L, stockCode, OrderMethod.BUY, new BigDecimal("99"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        Order activeOrder = limitBuy(2L, new BigDecimal("99"), 10);
        // 체결가는 event.price()=100이므로 필요한 홀딩 금액은 100*10=1000
        OrderHold activeHold = hold(activeOrder, new BigDecimal("1000"), 2L);

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(exhaustedEntry, activeEntry));
        // fillCommands에는 orderId=2(활성)만 들어가므로 findAllById에는 [2]만 전달됨
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(activeOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));
        when(executionService.record(eq(activeOrder), eq(event.price()), eq(10)))
                .thenReturn(createExecution(activeOrder, event.price(), 10));
        when(orderHoldRepository.findById(2L))
                .thenReturn(Optional.of(activeHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        // 활성 주문 체결에 따른 삭제 1회 + 소진 엔트리 삭제 1회, 정확히 orderId=1과 orderId=2 각각 1회
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(redisOrderBookRepository, times(1)).removeOrder(2L, stockCode, OrderMethod.BUY);
        verify(redisOrderBookRepository, times(2)).removeOrder(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("케이스2: 할당 break 이후에 위치한 소진 엔트리도 여전히 Redis에서 삭제된다 (전체 순회 시맨틱)")
    void distributeEvent_ExhaustedEntryAfterAllocationBreak_StillRemoved() {
        // Given
        String stockCode = "005930";
        // 이벤트 수량 10 → entry1(활성, remaining=10)만으로 완전히 소진되어 할당 루프가 break됨
        LimitOrderFillEvent event = sellEvent(10);

        OrderBookEntry activeEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );
        // break 이후 위치(정렬 후 두 번째)에 소진된 엔트리
        OrderBookEntry exhaustedAfterBreak = new OrderBookEntry(
                2L, stockCode, OrderMethod.BUY, new BigDecimal("99"),
                0, 10, Instant.now().toEpochMilli(), 1L
        );

        Order activeOrder = limitBuy(1L, new BigDecimal("100"), 10);
        OrderHold activeHold = hold(activeOrder, new BigDecimal("1000"), 1L);

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(activeEntry, exhaustedAfterBreak));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(activeOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));
        when(executionService.record(eq(activeOrder), eq(event.price()), eq(10)))
                .thenReturn(createExecution(activeOrder, event.price(), 10));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(activeHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        // 할당 루프는 remainingQuantity(이벤트)가 0이 되는 순간 break하여 exhaustedAfterBreak를 못 보지만,
        // 소진 삭제 루프는 orderedEntries 전체를 별도로 순회하므로 여전히 삭제된다.
        verify(redisOrderBookRepository, times(1)).removeOrder(2L, stockCode, OrderMethod.BUY);
    }

    @Test
    @DisplayName("케이스3: 모든 후보가 소진 상태면 fillCommands가 비어 조기 반환되고 removeOrder는 전혀 호출되지 않는다 (현재 누수 동작을 그대로 고정)")
    void distributeEvent_AllCandidatesExhausted_EarlyReturnNeverRemoves() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = sellEvent(10);

        OrderBookEntry exhausted1 = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                0, 10, Instant.now().toEpochMilli(), 1L
        );
        OrderBookEntry exhausted2 = new OrderBookEntry(
                2L, stockCode, OrderMethod.BUY, new BigDecimal("99"),
                0, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(exhausted1, exhausted2));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        // L109-111의 조기 반환으로 인해 소진 삭제 루프에 도달하지 못한다 (현재의 리소스 누수를 있는 그대로 고정)
        verify(redisOrderBookRepository, never()).removeOrder(anyLong(), anyString(), any());
        verify(orderRepository, never()).findAllById(any());
    }

    @Test
    @DisplayName("케이스4: 소진된 엔트리들은 정렬된 순서대로 Redis에서 삭제된다")
    void distributeEvent_MultipleExhaustedEntries_RemovedInSortedOrder() {
        // Given
        String stockCode = "005930";
        // 매도 이벤트 → 매수 후보는 가격 높은 순 우선. 활성 엔트리로 이벤트 수량을 모두 소진시켜
        // 소진 엔트리들만 별도 삭제 루프에서 처리되게 한다.
        LimitOrderFillEvent event = sellEvent(10);

        OrderBookEntry activeEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );
        // 정렬 후 순서: price desc → 99(orderId=2), 98(orderId=3), 97(orderId=4)
        OrderBookEntry exhaustedHighest = new OrderBookEntry(
                2L, stockCode, OrderMethod.BUY, new BigDecimal("99"),
                0, 10, Instant.now().toEpochMilli(), 1L
        );
        OrderBookEntry exhaustedMiddle = new OrderBookEntry(
                3L, stockCode, OrderMethod.BUY, new BigDecimal("98"),
                0, 10, Instant.now().toEpochMilli(), 1L
        );
        OrderBookEntry exhaustedLowest = new OrderBookEntry(
                4L, stockCode, OrderMethod.BUY, new BigDecimal("97"),
                0, 10, Instant.now().toEpochMilli(), 1L
        );

        Order activeOrder = limitBuy(1L, new BigDecimal("100"), 10);
        OrderHold activeHold = hold(activeOrder, new BigDecimal("1000"), 1L);

        // 입력 순서를 일부러 정렬 순서와 다르게 준다 (서비스가 sortByPriority로 다시 정렬함을 검증)
        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(exhaustedLowest, activeEntry, exhaustedHighest, exhaustedMiddle));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(activeOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));
        when(executionService.record(eq(activeOrder), eq(event.price()), eq(10)))
                .thenReturn(createExecution(activeOrder, event.price(), 10));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(activeHold));

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).hasSize(1);
        InOrder inOrder = inOrder(redisOrderBookRepository);
        inOrder.verify(redisOrderBookRepository).removeOrder(2L, stockCode, OrderMethod.BUY); // 99원, 먼저
        inOrder.verify(redisOrderBookRepository).removeOrder(3L, stockCode, OrderMethod.BUY); // 98원
        inOrder.verify(redisOrderBookRepository).removeOrder(4L, stockCode, OrderMethod.BUY); // 97원, 마지막
    }
}
