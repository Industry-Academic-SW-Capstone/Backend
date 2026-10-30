package grit.stockIt.domain.matching.queue;

import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.order.entity.OrderMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ExecutionReportPublisher")
class ExecutionReportPublisherTest {

    private static final ExecutionReport REPORT = ExecutionReport.fill(
            10L, 3L, 77L, "005930", OrderMethod.BUY, new BigDecimal("70000"), 2, 1_700_000_000_000L);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, ExecutionReport> kafkaTemplate = mock(KafkaTemplate.class);

    private final ExecutionReportPublisher publisher = new ExecutionReportPublisher(kafkaTemplate);

    @Test
    @DisplayName("계좌 ID 를 키로 exchange.reports 에 보낸다")
    void publish_keyedByAccount() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ExecutionReport.class)))
                .thenReturn(new CompletableFuture<>());

        publisher.publish(REPORT);

        verify(kafkaTemplate).send(ExchangeTopics.REPORTS, "77", REPORT);
    }

    @Test
    @DisplayName("전송이 실패해도 체결 워커로 예외를 던지지 않는다 — 정산 없는 체결은 복구 배치가 집는다")
    void publish_failure_doesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ExecutionReport.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatCode(() -> publisher.publish(REPORT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전송 호출 자체가 예외를 던져도 삼킨다")
    void publish_sendThrows_doesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any(ExecutionReport.class)))
                .thenThrow(new IllegalStateException("max.block.ms exceeded"));

        assertThatCode(() -> publisher.publish(REPORT)).doesNotThrowAnyException();
    }
}
