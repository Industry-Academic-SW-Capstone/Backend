package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator.QueuedFillOutcome;
import grit.stockIt.domain.order.service.OrderCancelService;
import grit.stockIt.domain.order.service.QueuedCancelOutcome;
import grit.stockIt.domain.settlement.queue.SettlementRequestPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 체결 워커. 파티션 하나를 스레드 하나가 맡아 그 안의 명령(체결, 취소, 만료)을 도착 순서대로 처리한다.
// 체결 결과는 정산 큐로 넘긴다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingCommandConsumer {

    private final LimitOrderFillCoordinator limitOrderFillCoordinator;
    private final OrderCancelService orderCancelService;
    private final SettlementRequestPublisher settlementRequestPublisher;
    private final MatchingQueueMetrics metrics;

    // 리스너 id·그룹은 지표 태그와 커밋된 offset 의 이름이다. 바꾸면 그룹이 처음부터 다시 읽는다.
    @KafkaListener(
            id = "matching-fill",
            topics = MatchingTopics.COMMANDS,
            groupId = "matching-fill",
            concurrency = "${matching.queue.fill-concurrency:24}",
            properties = "spring.json.value.default.type=grit.stockIt.domain.matching.queue.MatchingCommand"
    )
    public void consume(ConsumerRecord<String, MatchingCommand> record) {
        MatchingCommand command = record.value();
        command.validate();
        CommandPosition position = new CommandPosition(record.topic(), record.partition(), record.offset());

        // switch 식이라 명령 종류가 늘면 여기서 컴파일이 깨진다. 문(statement)이면 빠뜨린 종류가 조용히 넘어가 유실된다.
        Runnable handler = switch (command.type()) {
            case FILL -> () -> fill(command, position);
            case CANCEL -> () -> cancel(command, position);
            case EXPIRE -> () -> expire(command, position);
        };
        handler.run();
    }

    private void fill(MatchingCommand command, CommandPosition position) {
        metrics.pickedUp(command.enqueuedAt());
        QueuedFillOutcome outcome = limitOrderFillCoordinator.processQueuedFill(
                command.stockCode(), command.toFillEvent(), position);
        if (outcome.duplicate()) {
            metrics.skippedAsDuplicate();
            return;
        }

        long filledAt = System.currentTimeMillis();
        for (FilledExecution execution : outcome.executions()) {
            settlementRequestPublisher.publish(execution.executionId(), execution.accountId(), filledAt);
        }
        metrics.filled(command.enqueuedAt());
    }

    private void cancel(MatchingCommand command, CommandPosition position) {
        logIfDuplicate(orderCancelService.cancelOnce(command.stockCode(), command.orderId(), position), command, position);
    }

    private void expire(MatchingCommand command, CommandPosition position) {
        logIfDuplicate(orderCancelService.expireOnce(command.stockCode(), command.orderId(), position), command, position);
    }

    private void logIfDuplicate(QueuedCancelOutcome outcome, MatchingCommand command, CommandPosition position) {
        if (outcome == QueuedCancelOutcome.DUPLICATE) {
            log.info("이미 반영한 명령이라 건너뜁니다. type={} orderId={} position={}",
                    command.type(), command.orderId(), position);
        }
    }
}
