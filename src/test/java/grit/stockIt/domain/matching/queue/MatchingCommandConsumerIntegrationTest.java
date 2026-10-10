package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkRepository;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.global.support.KafkaIntegrationTestSupport;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("체결 워커 (Kafka 통합)")
class MatchingCommandConsumerIntegrationTest extends KafkaIntegrationTestSupport {

    private static final String STOCK_CODE = "005930";

    @Autowired
    private FillCommandPublisher fillCommandPublisher;

    @Autowired
    private ConsumerWatermarkRepository consumerWatermarkRepository;

    @Test
    @DisplayName("입구에 넣은 명령을 워커가 꺼내 처리하고 그 위치를 기록한다")
    void published_consumed_watermarkAdvanced() {
        assertThat(fillCommandPublisher).isInstanceOf(KafkaFillCommandPublisher.class);

        FillDispatchResult result = fillCommandPublisher.publish(STOCK_CODE, fillEvent(1));

        FillDispatchResult.Queued queued = (FillDispatchResult.Queued) result;
        awaitWatermarkReaches(queued);
    }

    @Test
    @DisplayName("읽을 수 없는 메시지는 DLT 로 보내고, 같은 파티션의 다음 명령은 막히지 않는다")
    void unreadableMessage_toDlt_partitionKeepsFlowing() throws Exception {
        String garbage = "not-json-" + UUID.randomUUID();
        sendRaw(garbage.getBytes(StandardCharsets.UTF_8));

        FillDispatchResult.Queued next = (FillDispatchResult.Queued) fillCommandPublisher.publish(STOCK_CODE, fillEvent(1));

        awaitWatermarkReaches(next);
        assertThat(findDeadLetter(MatchingTopics.COMMANDS, value -> value.equals(garbage))).isTrue();
    }

    @Test
    @DisplayName("검증에 실패한 명령은 재시도하지 않고 DLT 로 보낸다")
    void invalidCommand_toDlt_partitionKeepsFlowing() {
        String invalidEventId = "invalid-" + UUID.randomUUID();
        fillCommandPublisher.publish(STOCK_CODE, new LimitOrderFillEvent(
                invalidEventId, OrderMethod.SELL, new BigDecimal("100"), 0, System.currentTimeMillis()));

        FillDispatchResult.Queued next = (FillDispatchResult.Queued) fillCommandPublisher.publish(STOCK_CODE, fillEvent(1));

        awaitWatermarkReaches(next);
        assertThat(findDeadLetter(MatchingTopics.COMMANDS, value -> value.contains(invalidEventId))).isTrue();
    }

    private void awaitWatermarkReaches(FillDispatchResult.Queued queued) {
        await().atMost(WAIT).until(() -> lastOffsetOf(queued.partition()) >= queued.offset());
    }

    private long lastOffsetOf(int partition) {
        return consumerWatermarkRepository.findAll().stream()
                .filter(row -> row.getId().getTopic().equals(MatchingTopics.COMMANDS)
                        && row.getId().getPartitionNo() == partition)
                .mapToLong(ConsumerWatermark::getLastOffset)
                .findFirst()
                .orElse(-1L);
    }

    private LimitOrderFillEvent fillEvent(int quantity) {
        return new LimitOrderFillEvent(
                UUID.randomUUID().toString(), OrderMethod.SELL, new BigDecimal("100"), quantity, System.currentTimeMillis());
    }

    private void sendRaw(byte[] value) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(MatchingTopics.COMMANDS, STOCK_CODE, value)).get();
        }
    }
}
