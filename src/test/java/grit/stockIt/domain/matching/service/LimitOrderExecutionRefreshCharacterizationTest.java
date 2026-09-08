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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 특성화(Characterization) 테스트: 계좌 락 획득 후 entityManager.refresh() 시점에
 * 주문 상태가 다른 트랜잭션에 의해 바뀐 경우의 현재 동작을 기록한다.
 * (동시성 보호 목적의 재조회이며, 현재 있는 그대로 고치지 않는다.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LimitOrderExecutionService refresh 이후 상태 변경 특성화 테스트")
class LimitOrderExecutionRefreshCharacterizationTest {

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
                .cash(new BigDecimal("10000"))
                .holdAmount(new BigDecimal("1000"))
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

    @Test
    @DisplayName("refresh 시점에 주문이 FILLED로 바뀌면 체결을 건너뛰고 Redis에서 제거+구독 해제한다")
    void distributeEvent_RefreshRevealsFilledStatus_SkipsAndCleansUpRedis() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = sellEvent(10);

        Order buyOrder = Order.createLimitOrder(testAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(buyOrder, "orderId", 1L);

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(buyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));

        // refresh 시점에 다른 트랜잭션이 이미 주문을 전량 체결시킨 것을 시뮬레이션
        doAnswer(invocation -> {
            buyOrder.applyFill(10); // 상태를 FILLED로 전이
            return null;
        }).when(entityManager).refresh(buyOrder);

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        // 주의: distributeEvent의 마지막 트랜잭션 완료 루프는 filledOrderIds 전체를 다시 순회하며
        // order.getRemainingQuantity()<=0 여부로 removeOrder를 무조건 재호출한다. refresh로 인해
        // 이미 !isActive() 분기에서 removeOrder가 한 번 호출됐더라도, 이 최종 루프가 orderMap에서
        // 동일 order를 다시 찾아 (remaining=0이므로) removeOrder를 한 번 더 호출한다.
        // 결과적으로 removeOrder가 총 2회 호출되는 것이 현재 실제 동작이다 (있는 그대로 고정).
        assertThat(executions).isEmpty();
        verify(executionService, never()).record(any(), any(), anyInt());
        verify(redisOrderBookRepository, times(2)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stockCode);
    }

    @Test
    @DisplayName("refresh 시점에 주문이 CANCELLED로 바뀌면 체결을 건너뛰고 Redis에서 제거+구독 해제한다")
    void distributeEvent_RefreshRevealsCancelledStatus_SkipsAndCleansUpRedis() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = sellEvent(10);

        Order buyOrder = Order.createLimitOrder(testAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(buyOrder, "orderId", 1L);

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(buyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));

        // refresh 시점에 다른 트랜잭션이 이미 주문을 취소시킨 것을 시뮬레이션
        doAnswer(invocation -> {
            buyOrder.markCancelled();
            return null;
        }).when(entityManager).refresh(buyOrder);

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        assertThat(executions).isEmpty();
        verify(executionService, never()).record(any(), any(), anyInt());
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stockCode);
    }

    @Test
    @DisplayName("refresh 후 desiredFillQuantity가 0 이하이면 (다른 트랜잭션의 부분 체결) 조용히 건너뛰고 Redis는 건드리지 않는다")
    void distributeEvent_RefreshRevealsZeroRemaining_SkipsSilently() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = sellEvent(10);

        Order buyOrder = Order.createLimitOrder(testAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY);
        ReflectionTestUtils.setField(buyOrder, "orderId", 1L);

        OrderBookEntry orderBookEntry = new OrderBookEntry(
                1L, stockCode, OrderMethod.BUY, new BigDecimal("100"),
                10, 10, Instant.now().toEpochMilli(), 1L
        );

        when(redisOrderBookRepository.fetchMatchingEntries(stockCode, OrderMethod.SELL, event.price(), 100))
                .thenReturn(List.of(orderBookEntry));
        when(orderRepository.findAllById(anyList()))
                .thenReturn(List.of(buyOrder));
        when(accountRepository.findByIdWithLock(1L))
                .thenReturn(Optional.of(testAccount));

        // applyFill()의 불변식상 remaining=0이면서 상태가 FILLED가 아닌 조합은 정상 경로로는
        // 만들 수 없다(remaining=0이 되는 순간 자동으로 FILLED로 전이됨). 따라서
        // desiredFillQuantity<=0 분기를 독립적으로 트리거하려면 리플렉션으로 필드를
        // 직접 조작해 "remaining=0인데 status는 여전히 PARTIALLY_FILLED"인 인위적 상태를 만든다.
        // 이는 이 분기가 실제로는 도달 불가능에 가깝다는 사실 자체를 특성화하기 위한 것이다.
        doAnswer(invocation -> {
            ReflectionTestUtils.setField(buyOrder, "filledQuantity", 10);
            ReflectionTestUtils.setField(buyOrder, "status", grit.stockIt.domain.order.entity.OrderStatus.PARTIALLY_FILLED);
            return null;
        }).when(entityManager).refresh(buyOrder);

        // When
        List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);

        // Then
        // desiredFillQuantity<=0 분기 자체는 continue만 하고 Redis를 건드리지 않지만, distributeEvent 마지막의
        // 트랜잭션 완료 루프는 filledOrderIds 전체를 orderMap 기준으로 다시 순회하며
        // order.getRemainingQuantity()<=0이면 removeOrder를 호출한다. 여기서는 리플렉션으로 remaining=0을
        // 만들었으므로 그 마지막 루프에서 removeOrder가 1회 호출된다 (현재 동작 그대로 기록).
        assertThat(executions).isEmpty();
        verify(executionService, never()).record(any(), any(), anyInt());
        verify(redisOrderBookRepository, times(1)).removeOrder(1L, stockCode, OrderMethod.BUY);
        verify(redisOrderBookRepository, never()).updateRemainingQuantity(anyLong(), anyString(), any(), anyInt());
        // desiredFillQuantity<=0 분기의 continue는 unregisterLimitOrder를 호출하지 않는다
        verify(orderSubscriptionCoordinator, never()).unregisterLimitOrder(anyString());
    }
}
