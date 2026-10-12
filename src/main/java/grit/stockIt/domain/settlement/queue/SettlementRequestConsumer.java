package grit.stockIt.domain.settlement.queue;

import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 정산 워커. 같은 요청이 두 번 와도 settle() 이 settlement 의 execution_id 유니크로 한 번만 반영한다.
@Component
public class SettlementRequestConsumer {

    private final ExecutionSettlementService executionSettlementService;
    private final Timer settlementDelay;

    public SettlementRequestConsumer(ExecutionSettlementService executionSettlementService, MeterRegistry registry) {
        this.executionSettlementService = executionSettlementService;
        this.settlementDelay = Timer.builder("settlement.e2e")
                .description("체결이 커밋된 뒤 정산이 커밋될 때까지 — 잔고에 늦게 반영되는 시간").register(registry);
    }

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
        settlementDelay.record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - request.filledAt())));
    }
}
