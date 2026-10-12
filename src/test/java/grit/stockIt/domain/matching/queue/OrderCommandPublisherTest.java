package grit.stockIt.domain.matching.queue;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OrderCommandPublisher")
class OrderCommandPublisherTest {

    private static final String STOCK_CODE = "005930";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, MatchingCommand> kafkaTemplate = mock(KafkaTemplate.class);

    private final OrderCommandPublisher publisher =
            new OrderCommandPublisher(new MatchingCommandSender(kafkaTemplate, Duration.ofMillis(200)));

    @Test
    @DisplayName("취소는 체결과 같은 토픽에 종목 코드를 키로 보낸다 — 같은 종목의 체결과 한 줄에 선다")
    void cancel_sameTopicAndKeyAsFill() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(MatchingTopics.COMMANDS, 3), 10L, 0, 0L, 0, 0);
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class))).thenReturn(CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>(MatchingTopics.COMMANDS, STOCK_CODE, null), metadata)));

        assertThat(publisher.cancel(STOCK_CODE, 9L)).isTrue();

        ArgumentCaptor<MatchingCommand> captor = ArgumentCaptor.forClass(MatchingCommand.class);
        verify(kafkaTemplate).send(eq(MatchingTopics.COMMANDS), eq(STOCK_CODE), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MatchingCommandType.CANCEL);
        assertThat(captor.getValue().orderId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("브로커 확인을 받지 못하면 false — 호출자는 접수 실패로 알린다")
    void cancel_notAcked_returnsFalse() {
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThat(publisher.cancel(STOCK_CODE, 9L)).isFalse();
    }

    @Test
    @DisplayName("만료도 같은 토픽·같은 키로 보내고 종류만 EXPIRE 다")
    void expire_sameTopicAndKey() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition(MatchingTopics.COMMANDS, 3), 11L, 0, 0L, 0, 0);
        when(kafkaTemplate.send(anyString(), anyString(), any(MatchingCommand.class))).thenReturn(CompletableFuture.completedFuture(
                new SendResult<>(new ProducerRecord<>(MatchingTopics.COMMANDS, STOCK_CODE, null), metadata)));

        assertThat(publisher.expire(STOCK_CODE, 9L)).isTrue();

        ArgumentCaptor<MatchingCommand> captor = ArgumentCaptor.forClass(MatchingCommand.class);
        verify(kafkaTemplate).send(eq(MatchingTopics.COMMANDS), eq(STOCK_CODE), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(MatchingCommandType.EXPIRE);
        assertThat(captor.getValue().orderId()).isEqualTo(9L);
    }
}
