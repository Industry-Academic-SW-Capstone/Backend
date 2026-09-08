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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 특성화(Characterization) 테스트: Redis 실패 시 롤백 경로.
 * distributeEvent() 마지막 구간에서 redisOrderBookRepository 호출이 실패하면
 * 예외가 그대로 전파되어 트랜잭션이 롤백되고, 이중 체결을 방지한다.
 * 현재 동작을 있는 그대로 기록하며 고치지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LimitOrderExecutionService Redis 실패 롤백 특성화 테스트")
class LimitOrderExecutionRollbackCharacterizationTest {

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
    private Order testBuyOrder;
    private OrderHold testOrderHold;

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

        testBuyOrder = Order.createLimitOrder(
                testAccount, testStock, new BigDecimal("100"), 10, OrderMethod.BUY
        );
        ReflectionTestUtils.setField(testBuyOrder, "orderId", 1L);

        testOrderHold = OrderHold.create(testBuyOrder, testAccount, new BigDecimal("1000"));
        ReflectionTestUtils.setField(testOrderHold, "orderId", 1L);
    }

    private Execution createExecution(Order order, BigDecimal price, int quantity) {
        Execution execution = Execution.of(order, price, quantity);
        long executionId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        ReflectionTestUtils.setField(execution, "executionId", executionId);
        return execution;
    }

    @Test
    @DisplayName("전량 체결 후 removeOrder 호출이 실패하면 예외가 distributeEvent 밖으로 전파된다")
    void distributeEvent_RemoveOrderFails_FullyFilled_PropagatesException() {
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

        doThrow(new RuntimeException("redis removeOrder failure"))
                .when(redisOrderBookRepository).removeOrder(anyLong(), anyString(), any());

        // When / Then
        assertThatThrownBy(() -> limitOrderExecutionService.distributeEvent(stockCode, event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis removeOrder failure");
    }

    @Test
    @DisplayName("부분 체결 후 updateRemainingQuantity 호출이 실패하면 예외가 distributeEvent 밖으로 전파된다")
    void distributeEvent_UpdateRemainingQuantityFails_PartiallyFilled_PropagatesException() {
        // Given
        String stockCode = "005930";
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                OrderMethod.SELL,
                new BigDecimal("100"),
                5, // 부분 체결
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
        when(executionService.record(eq(testBuyOrder), eq(event.price()), eq(5)))
                .thenReturn(createExecution(testBuyOrder, event.price(), 5));
        when(orderHoldRepository.findById(1L))
                .thenReturn(Optional.of(testOrderHold));

        doThrow(new RuntimeException("redis updateRemainingQuantity failure"))
                .when(redisOrderBookRepository)
                .updateRemainingQuantity(anyLong(), anyString(), any(), anyInt());

        // When / Then
        assertThatThrownBy(() -> limitOrderExecutionService.distributeEvent(stockCode, event))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis updateRemainingQuantity failure");
    }
}
