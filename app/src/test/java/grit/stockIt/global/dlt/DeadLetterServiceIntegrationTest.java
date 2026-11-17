package grit.stockIt.global.dlt;

import grit.stockIt.global.support.KafkaIntegrationTestSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 운영 토픽으로 다시 보내면 그 토픽의 워커가 받으므로, 이 테스트만 쓰는 토픽으로 확인한다.
@TestPropertySource(properties = "dlt.topics=" + DeadLetterServiceIntegrationTest.DLT)
@DisplayName("DLT 재전송·건너뜀 (Kafka 통합 테스트)")
class DeadLetterServiceIntegrationTest extends KafkaIntegrationTestSupport {

    static final String ORIGINAL = "dlt-test.events";
    static final String DLT = ORIGINAL + ".DLT";

    @Autowired private DeadLetterService deadLetterService;

    @BeforeAll
    static void createTestTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(ORIGINAL, 1, (short) 1), new NewTopic(DLT, 2, (short) 1)))
                    .all().get();
        }
    }

    @Test
    @DisplayName("미처리 메시지를 실패 사유와 함께 보여 주고, 재전송하면 원래 토픽에 같은 키로 가고 다시 보이지 않는다")
    void pendingThenReplay() {
        String marker = UUID.randomUUID().toString();
        deadLetter("k1", "{\"n\":1,\"m\":\"" + marker + "\"}");
        deadLetter("k2", "{\"n\":2,\"m\":\"" + marker + "\"}");

        DeadLetterService.DeadLetterBatch pending = deadLetterService.pending(DLT, 10);
        assertThat(pending.messages())
                .filteredOn(letter -> letter.payload().contains(marker))
                .hasSize(2)
                .allSatisfy(letter -> {
                    assertThat(letter.originalTopic()).isEqualTo(ORIGINAL);
                    assertThat(letter.exceptionMessage()).isEqualTo("정산 실패(테스트)");
                });

        int replayed = deadLetterService.replay(DLT);

        assertThat(replayed).isGreaterThanOrEqualTo(2);
        assertThat(readOriginal(marker)).extracting(ConsumerRecord::key).containsExactlyInAnyOrder("k1", "k2");
        assertThat(deadLetterService.pending(DLT, 10).pendingCount()).isZero();
    }

    @Test
    @DisplayName("건너뛰면 보내지 않고 처리한 것으로 표시한다")
    void skip() {
        String marker = UUID.randomUUID().toString();
        deadLetter("k3", "{\"m\":\"" + marker + "\"}");

        deadLetterService.skip(DLT);

        assertThat(deadLetterService.pending(DLT, 10).pendingCount()).isZero();
        assertThat(readOriginal(marker)).isEmpty();
    }

    @Test
    @DisplayName("다루지 않는 토픽은 거절한다")
    void unknownTopic_rejected() {
        assertThatThrownBy(() -> deadLetterService.pending("member.events", 10))
                .hasMessageContaining("다룰 수 없는 DLT");
    }

    private static void deadLetter(String key, String payload) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            ProducerRecord<String, String> record = new ProducerRecord<>(DLT, key, payload);
            record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, ORIGINAL.getBytes(StandardCharsets.UTF_8));
            record.headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "정산 실패(테스트)".getBytes(StandardCharsets.UTF_8));
            producer.send(record);
            producer.flush();
        }
    }

    // 원래 토픽을 처음부터 읽어 이 테스트가 넣은 메시지만 모은다.
    private static List<ConsumerRecord<String, String>> readOriginal(String marker) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(ORIGINAL));
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(marker)) {
                        found.add(record);
                    }
                }
            }
        }
        return found;
    }
}
