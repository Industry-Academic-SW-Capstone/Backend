package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator.QueuedFillOutcome;
import grit.stockIt.domain.matching.service.OrderAcceptService;
import grit.stockIt.domain.matching.service.OrderCancelService;
import grit.stockIt.domain.matching.service.QueuedCancelOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 명령 하나를 검증하고 종류별 서비스로 넘긴다. 지표는 handle 이 아니라 커밋 뒤 recordMetrics 로 남긴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingCommandHandler {

    private final LimitOrderFillCoordinator limitOrderFillCoordinator;
    private final OrderAcceptService orderAcceptService;
    private final OrderCancelService orderCancelService;
    private final MatchingQueueMetrics metrics;

    public record CommandResult(MatchingCommandType type, boolean duplicate, long enqueuedAt, long pickedUpAt) {
    }

    public CommandResult handle(MatchingCommand command, CommandPosition position) {
        long pickedUpAt = System.currentTimeMillis();
        command.validate();

        // switch 식이라 명령 종류가 늘면 여기서 컴파일이 깨진다. 문(statement)이면 빠뜨린 종류가 조용히 넘어가 유실된다.
        boolean duplicate = switch (command.type()) {
            case ORDER -> {
                orderAcceptService.acceptOnce(command, position);
                yield false;
            }
            case FILL -> {
                QueuedFillOutcome outcome = limitOrderFillCoordinator.processQueuedFill(
                        command.stockCode(), command.toFillEvent(), position);
                yield outcome.duplicate();
            }
            case CANCEL -> orderCancelService.cancelOnce(command.stockCode(), command.orderId(), position)
                    == QueuedCancelOutcome.DUPLICATE;
            case EXPIRE -> orderCancelService.expireOnce(command.stockCode(), command.orderId(), position)
                    == QueuedCancelOutcome.DUPLICATE;
        };
        return new CommandResult(command.type(), duplicate, command.enqueuedAt(), pickedUpAt);
    }

    public void recordMetrics(CommandResult result) {
        switch (result.type()) {
            case FILL -> {
                metrics.pickedUp(result.enqueuedAt(), result.pickedUpAt());
                if (result.duplicate()) {
                    metrics.skippedAsDuplicate();
                } else {
                    metrics.filled(result.enqueuedAt());
                }
            }
            case CANCEL, EXPIRE -> {
                if (result.duplicate()) {
                    log.info("이미 반영한 명령이라 건너뜁니다. type={}", result.type());
                }
            }
            case ORDER -> { }
        }
    }
}
