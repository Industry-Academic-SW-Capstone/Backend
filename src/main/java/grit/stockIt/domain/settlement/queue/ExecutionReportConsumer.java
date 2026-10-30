package grit.stockIt.domain.settlement.queue;

import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.order.service.OrderHoldReleaseService;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 정산 워커. 거래소의 결과 통보를 계좌 키 순서대로 받아 계좌에 반영한다 — 체결은 정산, 취소·만료는 홀딩 해제.
// 같은 통보가 두 번 와도 정산은 execution_id 유니크, 홀딩 해제는 hold_release 의 주문 번호로 한 번만 반영한다.
@Component
public class ExecutionReportConsumer {

    private final ExecutionSettlementService executionSettlementService;
    private final OrderHoldReleaseService orderHoldReleaseService;
    private final Timer settlementDelay;

    public ExecutionReportConsumer(ExecutionSettlementService executionSettlementService,
                                   OrderHoldReleaseService orderHoldReleaseService, MeterRegistry registry) {
        this.executionSettlementService = executionSettlementService;
        this.orderHoldReleaseService = orderHoldReleaseService;
        this.settlementDelay = Timer.builder("settlement.e2e")
                .description("체결이 커밋된 뒤 정산이 커밋될 때까지 — 잔고에 늦게 반영되는 시간").register(registry);
    }

    // 리스너 id·그룹은 지표 태그와 커밋된 offset 의 이름이다. 대시보드의 정산 lag 이 이 이름을 본다.
    @KafkaListener(
            id = "settlement",
            topics = ExchangeTopics.REPORTS,
            groupId = "settlement",
            concurrency = "${settlement.queue.concurrency:8}",
            containerFactory = "settlementListenerContainerFactory",
            properties = "spring.json.value.default.type=grit.stockIt.contract.ExecutionReport"
    )
    public void consume(ExecutionReport report) {
        report.validate();
        // switch 식이라 결과 종류가 늘면 여기서 컴파일이 깨진다.
        Runnable handler = switch (report.type()) {
            case FILL -> () -> settle(report);
            case CANCELED, EXPIRED -> () -> orderHoldReleaseService.release(report);
        };
        handler.run();
    }

    private void settle(ExecutionReport report) {
        executionSettlementService.settle(report);
        settlementDelay.record(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - report.filledAt())));
    }
}
