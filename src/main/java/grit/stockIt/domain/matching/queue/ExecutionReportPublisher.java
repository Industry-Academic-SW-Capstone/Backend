package grit.stockIt.domain.matching.queue;

import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.contract.ExecutionReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 브로커 확인을 기다리지 않는다. 체결 워커는 종목을 한 줄로 처리하므로 기다린 시간이 그대로 종목당 천장을 낮춘다.
// 전송이 실패해도 체결은 이미 커밋돼 있고, 정산 없는 체결은 복구 배치가 집는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionReportPublisher {

    private final KafkaTemplate<String, ExecutionReport> kafkaTemplate;

    public void publish(ExecutionReport report) {
        try {
            kafkaTemplate.send(ExchangeTopics.REPORTS, String.valueOf(report.accountId()), report)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            logFailure(report, exception);
                        }
                    });
        } catch (RuntimeException e) {
            logFailure(report, e);
        }
    }

    private void logFailure(ExecutionReport report, Throwable cause) {
        log.error("결과 통보 전송 실패. 복구 배치가 처리한다. type={} executionId={} accountId={}",
                report.type(), report.executionId(), report.accountId(), cause);
    }
}
