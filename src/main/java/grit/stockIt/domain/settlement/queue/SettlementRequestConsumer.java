package grit.stockIt.domain.settlement.queue;

import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 정산 워커. 같은 요청이 두 번 와도 settle() 이 settlement 의 execution_id 유니크로 한 번만 반영한다.
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.queue.enabled", havingValue = "true")
public class SettlementRequestConsumer {

    private final ExecutionSettlementService executionSettlementService;

    @KafkaListener(
            id = "settlement",
            topics = SettlementTopics.REQUESTS,
            groupId = "settlement",
            concurrency = "${settlement.queue.concurrency:8}",
            containerFactory = "settlementListenerContainerFactory",
            properties = "spring.json.value.default.type=grit.stockIt.domain.settlement.queue.SettlementRequest"
    )
    public void consume(SettlementRequest request) {
        request.validate();
        executionSettlementService.settle(request.executionId());
    }
}
