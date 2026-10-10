package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator.QueuedFillOutcome;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.service.OrderCancelService;
import grit.stockIt.domain.order.service.QueuedCancelOutcome;
import grit.stockIt.domain.settlement.queue.SettlementRequestPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MatchingCommandConsumer")
class MatchingCommandConsumerTest {

    private static final CommandPosition POSITION = new CommandPosition(MatchingTopics.COMMANDS, 7, 42L);

    private final LimitOrderFillCoordinator coordinator = mock(LimitOrderFillCoordinator.class);
    private final SettlementRequestPublisher settlementRequestPublisher = mock(SettlementRequestPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrderCancelService orderCancelService = mock(OrderCancelService.class);
    private final MatchingCommandConsumer consumer = new MatchingCommandConsumer(
            coordinator, orderCancelService, settlementRequestPublisher, new MatchingQueueMetrics(registry));

    @Test
    @DisplayName("체결마다 그 계좌 키로 정산 요청을 보내고 처리 지표를 남긴다")
    void filled_publishesSettlementPerExecution() {
        when(coordinator.processQueuedFill(eq("005930"), any(), eq(POSITION))).thenReturn(new QueuedFillOutcome(false,
                List.of(new FilledExecution(501L, 77L), new FilledExecution(502L, 31L))));

        consumer.consume(record(fillCommand(1)));

        verify(settlementRequestPublisher).publish(eq(501L), eq(77L), anyLong());
        verify(settlementRequestPublisher).publish(eq(502L), eq(31L), anyLong());
        assertThat(registry.get("matching.fill.e2e").timer().count()).isEqualTo(1L);
        assertThat(registry.get("matching.fill.queue.wait").timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("이미 반영한 위치면 정산 요청을 보내지 않고 중복으로 센다")
    void duplicate_skipsSettlement() {
        when(coordinator.processQueuedFill(any(), any(), any())).thenReturn(new QueuedFillOutcome(true, List.of()));

        consumer.consume(record(fillCommand(1)));

        verify(settlementRequestPublisher, never()).publish(any(), any(), anyLong());
        assertThat(registry.get("matching.fill.duplicate").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("matching.fill.e2e").timer().count()).isZero();
    }

    @Test
    @DisplayName("검증에 실패하면 체결하지 않고 재시도 금지 예외를 던진다")
    void invalidCommand_throwsWithoutFilling() {
        assertThatThrownBy(() -> consumer.consume(record(fillCommand(0))))
                .isInstanceOf(InvalidMatchingCommandException.class);
        verify(coordinator, never()).processQueuedFill(any(), any(), any());
    }

    @Test
    @DisplayName("취소 명령은 그 위치와 함께 취소 처리로 넘기고 체결·정산은 하지 않는다")
    void cancel_delegatesToCancelService() {
        when(orderCancelService.cancelOnce("005930", 9L, POSITION)).thenReturn(QueuedCancelOutcome.CANCELLED);

        consumer.consume(record(MatchingCommand.cancel("005930", 9L, System.currentTimeMillis())));

        verify(orderCancelService).cancelOnce("005930", 9L, POSITION);
        verify(coordinator, never()).processQueuedFill(any(), any(), any());
        verify(settlementRequestPublisher, never()).publish(any(), any(), anyLong());
        assertThat(registry.get("matching.fill.e2e").timer().count()).isZero();
    }

    @Test
    @DisplayName("주문 번호가 없는 취소 명령은 처리하지 않고 재시도 금지 예외를 던진다")
    void cancelWithoutOrderId_throwsWithoutCancelling() {
        assertThatThrownBy(() -> consumer.consume(record(MatchingCommand.cancel("005930", null, 0L))))
                .isInstanceOf(InvalidMatchingCommandException.class);
        verify(orderCancelService, never()).cancelOnce(any(), any(), any());
    }

    private MatchingCommand fillCommand(int quantity) {
        return MatchingCommand.fill("005930",
                new LimitOrderFillEvent("evt-1", OrderMethod.SELL, new BigDecimal("200"), quantity, 1_700_000_000_000L),
                System.currentTimeMillis());
    }

    private ConsumerRecord<String, MatchingCommand> record(MatchingCommand command) {
        return new ConsumerRecord<>(POSITION.topic(), POSITION.partition(), POSITION.offset(), command.stockCode(), command);
    }
}
