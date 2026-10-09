package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import grit.stockIt.domain.order.entity.OrderMethod;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("KafkaFillCommandPublisher")
class KafkaFillCommandPublisherTest {

    private static final String STOCK_CODE = "005930";
    private static final LimitOrderFillEvent EVENT =
            new LimitOrderFillEvent("evt-1", OrderMethod.BUY, new BigDecimal("200"), 1, 1_700_000_000_000L);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, MatchingCommand> kafkaTemplate = mock(KafkaTemplate.class);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RedisMarketDataRepository redisMarketDataRepository = mock(RedisMarketDataRepository.class);

    private final KafkaFillCommandPublisher publisher = new KafkaFillCommandPublisher(
            kafkaTemplate, Duration.ofMillis(200), new MatchingQueueMetrics(registry), redisMarketDataRepository);

    @Test
    @DisplayName("종목 코드를 키로 matching.commands 에 보내고, 브로커 확인을 받으면 기록 위치를 돌려준다")
    void publish_acked_returnsQueued() {
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class)))
                .thenReturn(CompletableFuture.completedFuture(sendResult(7, 42L)));

        FillDispatchResult result = publisher.publish(STOCK_CODE, EVENT);

        assertThat(result).isEqualTo(new FillDispatchResult.Queued(7, 42L));
        ArgumentCaptor<MatchingCommand> captor = ArgumentCaptor.forClass(MatchingCommand.class);
        verify(kafkaTemplate).send(eq(MatchingTopics.COMMANDS), eq(STOCK_CODE), captor.capture());
        MatchingCommand command = captor.getValue();
        assertThat(command.type()).isEqualTo(MatchingCommandType.FILL);
        assertThat(command.toFillEvent()).isEqualTo(EVENT);
        assertThat(command.stockCode()).isEqualTo(STOCK_CODE);
        assertThat(command.enqueuedAt()).isPositive();
        assertThat(enqueueCount("ok")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("현재가는 큐에 넣을 때 갱신한다 — 워커까지 기다리면 큐가 밀린 만큼 시세도 늦는다")
    void publish_updatesLastPriceAtEntry() {
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class)))
                .thenReturn(new CompletableFuture<>());

        publisher.publish(STOCK_CODE, EVENT);

        verify(redisMarketDataRepository).updateLastPrice(STOCK_CODE, EVENT.price());
    }

    @Test
    @DisplayName("브로커가 기록을 거부하면 실패를 돌려준다")
    void publish_brokerError_returnsFailed() {
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.publish(STOCK_CODE, EVENT)).isInstanceOf(FillDispatchResult.Failed.class);
        assertThat(enqueueCount("fail")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("확인이 제한 시간 안에 오지 않으면 기다리지 않고 실패를 돌려준다")
    void publish_ackTimeout_returnsFailed() {
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class)))
                .thenReturn(new CompletableFuture<>());

        long start = System.nanoTime();
        FillDispatchResult result = publisher.publish(STOCK_CODE, EVENT);

        assertThat(result).isInstanceOf(FillDispatchResult.Failed.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("전송 호출 자체가 예외를 던져도 실패로 돌려준다")
    void publish_sendThrows_returnsFailed() {
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class)))
                .thenThrow(new IllegalStateException("max.block.ms exceeded"));

        assertThat(publisher.publish(STOCK_CODE, EVENT)).isInstanceOf(FillDispatchResult.Failed.class);
    }

    private double enqueueCount(String result) {
        return registry.get("matching.enqueue").tag("result", result).counter().count();
    }

    private SendResult<String, MatchingCommand> sendResult(int partition, long offset) {
        RecordMetadata metadata = new RecordMetadata(
                new TopicPartition(MatchingTopics.COMMANDS, partition), offset, 0, 0L, 0, 0);
        return new SendResult<>(new ProducerRecord<>(MatchingTopics.COMMANDS, STOCK_CODE, null), metadata);
    }
}
