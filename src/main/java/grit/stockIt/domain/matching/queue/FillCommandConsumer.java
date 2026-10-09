package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator;
import grit.stockIt.domain.matching.service.LimitOrderFillCoordinator.QueuedFillOutcome;
import grit.stockIt.domain.settlement.queue.SettlementRequestPublisher;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 체결 워커. 파티션 하나를 스레드 하나가 맡아 그 안의 명령을 순서대로 처리하고, 체결 결과를 정산 큐로 넘긴다.
@Component
@RequiredArgsConstructor
public class FillCommandConsumer {

    private final LimitOrderFillCoordinator limitOrderFillCoordinator;
    private final SettlementRequestPublisher settlementRequestPublisher;
    private final MatchingQueueMetrics metrics;

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
        metrics.pickedUp(command.enqueuedAt());
        CommandPosition position = new CommandPosition(record.topic(), record.partition(), record.offset());

        // switch 식이라 명령 종류가 늘면 여기서 컴파일이 깨진다. 문(statement)이면 빠뜨린 종류가 조용히 넘어가 유실된다.
        QueuedFillOutcome outcome = switch (command.type()) {
            case FILL -> limitOrderFillCoordinator.processQueuedFill(
                    command.stockCode(), command.toFillEvent(), position);
        };
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
}
