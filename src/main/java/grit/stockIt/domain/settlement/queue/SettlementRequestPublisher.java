package grit.stockIt.domain.settlement.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 브로커 확인을 기다리지 않는다. 체결 워커는 종목을 한 줄로 처리하므로 기다린 시간이 그대로 종목당 천장을 낮춘다.
// 전송이 실패해도 체결은 이미 커밋돼 있고, 정산 없는 체결은 복구 배치가 집는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementRequestPublisher {

    private final KafkaTemplate<String, SettlementRequest> kafkaTemplate;

    public void publish(Long executionId, Long accountId, long filledAt) {
        SettlementRequest request = new SettlementRequest(executionId, accountId, filledAt);
        try {
            kafkaTemplate.send(SettlementTopics.REQUESTS, String.valueOf(accountId), request)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            logFailure(request, exception);
                        }
                    });
        } catch (RuntimeException e) {
            logFailure(request, e);
        }
    }

    private void logFailure(SettlementRequest request, Throwable cause) {
        log.error("정산 요청 전송 실패. 복구 배치가 처리한다. executionId={} accountId={}",
                request.executionId(), request.accountId(), cause);
    }
}
