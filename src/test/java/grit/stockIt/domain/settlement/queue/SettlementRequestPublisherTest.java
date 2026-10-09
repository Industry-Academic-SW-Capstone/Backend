package grit.stockIt.domain.settlement.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SettlementRequestPublisher")
class SettlementRequestPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, SettlementRequest> kafkaTemplate = mock(KafkaTemplate.class);

    private final SettlementRequestPublisher publisher = new SettlementRequestPublisher(kafkaTemplate);

    @Test
    @DisplayName("계좌 ID 를 키로 settlement.requests 에 보낸다")
    void publish_keyedByAccount() {
        when(kafkaTemplate.send(anyString(), anyString(), any(SettlementRequest.class)))
                .thenReturn(new CompletableFuture<>());

        publisher.publish(10L, 77L, 1_700_000_000_000L);

        verify(kafkaTemplate).send(SettlementTopics.REQUESTS, "77", new SettlementRequest(10L, 77L, 1_700_000_000_000L));
    }

    @Test
    @DisplayName("전송이 실패해도 체결 워커로 예외를 던지지 않는다 — 정산 없는 체결은 복구 배치가 집는다")
    void publish_failure_doesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any(SettlementRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatCode(() -> publisher.publish(10L, 77L, 0L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("전송 호출 자체가 예외를 던져도 삼킨다")
    void publish_sendThrows_doesNotThrow() {
        when(kafkaTemplate.send(anyString(), anyString(), any(SettlementRequest.class)))
                .thenThrow(new IllegalStateException("max.block.ms exceeded"));

        assertThatCode(() -> publisher.publish(10L, 77L, 0L)).doesNotThrowAnyException();
    }
}
