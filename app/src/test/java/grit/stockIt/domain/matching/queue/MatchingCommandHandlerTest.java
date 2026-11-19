package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.MatchingCommandHandler.CommandResult;
import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator.QueuedFillOutcome;
import grit.stockIt.domain.matching.service.OrderAcceptService;
import grit.stockIt.domain.matching.service.OrderCancelService;
import grit.stockIt.domain.matching.service.QueuedCancelOutcome;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MatchingCommandHandler")
class MatchingCommandHandlerTest {

    private static final CommandPosition POSITION = new CommandPosition(MatchingTopics.COMMANDS, 7, 42L);

    private final LimitOrderFillCoordinator coordinator = mock(LimitOrderFillCoordinator.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderAcceptService orderAcceptService = mock(OrderAcceptService.class);
    private final OrderCancelService orderCancelService = mock(OrderCancelService.class);
    private final MatchingCommandHandler handler = new MatchingCommandHandler(
            coordinator, orderAcceptService, orderCancelService, new MatchingQueueMetrics(registry));

    @Test
    @DisplayName("체결 명령은 체결 처리로 넘기고 지표는 남기지 않는다")
    void fill_delegatesWithoutMetrics() {
        MatchingCommand command = fillCommand(1);
        when(coordinator.processQueuedFill(eq("005930"), any(), eq(POSITION))).thenReturn(new QueuedFillOutcome(false,
                List.of(new FilledExecution(501L, 11L, 77L, "005930", OrderMethod.BUY, new BigDecimal("70000"), 1, 1, 0))));

        CommandResult result = handler.handle(command, POSITION);

        assertThat(result).isEqualTo(new CommandResult(MatchingCommandType.FILL, false, command.enqueuedAt(),
                result.pickedUpAt()));
        assertThat(registry.get("matching.fill.e2e").timer().count()).isZero();
    }

    @Test
    @DisplayName("이미 반영한 위치면 중복으로 돌려준다")
    void fill_duplicate() {
        when(coordinator.processQueuedFill(any(), any(), any())).thenReturn(new QueuedFillOutcome(true, List.of()));

        CommandResult result = handler.handle(fillCommand(1), POSITION);

        assertThat(result.duplicate()).isTrue();
    }

    @Test
    @DisplayName("검증에 실패하면 체결하지 않고 재시도 금지 예외를 던진다")
    void invalidCommand_throwsWithoutFilling() {
        assertThatThrownBy(() -> handler.handle(fillCommand(0), POSITION))
                .isInstanceOf(InvalidMatchingCommandException.class);
        verify(coordinator, never()).processQueuedFill(any(), any(), any());
    }

    @Test
    @DisplayName("취소 명령은 그 위치와 함께 취소 처리로 넘기고 체결·정산은 하지 않는다")
    void cancel_delegatesToCancelService() {
        when(orderCancelService.cancelOnce("005930", 9L, POSITION)).thenReturn(QueuedCancelOutcome.DUPLICATE);

        CommandResult result = handler.handle(MatchingCommand.cancel("005930", 9L, 5L), POSITION);

        assertThat(result).isEqualTo(new CommandResult(MatchingCommandType.CANCEL, true, 5L, result.pickedUpAt()));
        verify(orderCancelService).cancelOnce("005930", 9L, POSITION);
        verify(coordinator, never()).processQueuedFill(any(), any(), any());
    }

    @Test
    @DisplayName("주문 명령은 그 위치와 함께 오더북 접수로 넘긴다")
    void order_delegatesToAcceptService() {
        MatchingCommand order = MatchingCommand.order("005930", 9L, 77L, OrderMethod.BUY, OrderType.LIMIT,
                new BigDecimal("70000"), 3, System.currentTimeMillis());

        CommandResult result = handler.handle(order, POSITION);

        assertThat(result.type()).isEqualTo(MatchingCommandType.ORDER);
        verify(orderAcceptService).acceptOnce(order, POSITION);
        verify(coordinator, never()).processQueuedFill(any(), any(), any());
    }

    @Test
    @DisplayName("만료 명령은 만료 처리로 넘긴다")
    void expire_delegatesToExpireOnce() {
        when(orderCancelService.expireOnce("005930", 9L, POSITION)).thenReturn(QueuedCancelOutcome.CANCELLED);

        CommandResult result = handler.handle(MatchingCommand.expire("005930", 9L, System.currentTimeMillis()), POSITION);

        assertThat(result.duplicate()).isFalse();
        verify(orderCancelService).expireOnce("005930", 9L, POSITION);
        verify(orderCancelService, never()).cancelOnce(any(), any(), any());
    }

    @Test
    @DisplayName("주문 번호가 없는 취소 명령은 처리하지 않고 재시도 금지 예외를 던진다")
    void cancelWithoutOrderId_throwsWithoutCancelling() {
        assertThatThrownBy(() -> handler.handle(MatchingCommand.cancel("005930", null, 0L), POSITION))
                .isInstanceOf(InvalidMatchingCommandException.class);
        verify(orderCancelService, never()).cancelOnce(any(), any(), any());
    }

    @Test
    @DisplayName("체결 결과를 기록하면 처리 지표를 남긴다")
    void recordMetrics_filled() {
        handler.recordMetrics(new CommandResult(MatchingCommandType.FILL, false, System.currentTimeMillis(), System.currentTimeMillis()));

        assertThat(registry.get("matching.fill.e2e").timer().count()).isEqualTo(1L);
        assertThat(registry.get("matching.fill.queue.wait").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("큐 대기는 꺼낸 시각까지만 재고 종단 지연은 기록 시점까지 잰다")
    void recordMetrics_queueWaitStopsAtPickedUp() {
        long enqueuedAt = System.currentTimeMillis() - 10_000;

        handler.recordMetrics(new CommandResult(MatchingCommandType.FILL, false, enqueuedAt, enqueuedAt + 100));

        assertThat(registry.get("matching.fill.queue.wait").timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(100.0);
        assertThat(registry.get("matching.fill.e2e").timer().totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(10_000.0);
    }

    @Test
    @DisplayName("중복 체결 결과를 기록하면 중복으로 센다")
    void recordMetrics_duplicate() {
        handler.recordMetrics(new CommandResult(MatchingCommandType.FILL, true, System.currentTimeMillis(), System.currentTimeMillis()));

        assertThat(registry.get("matching.fill.duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("matching.fill.e2e").timer().count()).isZero();
    }

    private MatchingCommand fillCommand(int quantity) {
        return MatchingCommand.fill("005930",
                new LimitOrderFillEvent("evt-1", OrderMethod.SELL, new BigDecimal("200"), quantity, 1_700_000_000_000L),
                System.currentTimeMillis());
    }
}
