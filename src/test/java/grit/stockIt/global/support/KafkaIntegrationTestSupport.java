package grit.stockIt.global.support;

import grit.stockIt.domain.matching.queue.MatchingTopics;
import grit.stockIt.domain.settlement.queue.SettlementTopics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

// 워커가 도는 통합 테스트. 브로커는 JVM 당 한 번 띄우고, 토픽은 운영처럼 미리 만든다(자동 생성에 기대지 않는다).
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=true",
        "matching.queue.fill-concurrency=" + KafkaIntegrationTestSupport.PARTITIONS
})
public abstract class KafkaIntegrationTestSupport extends IntegrationTestSupport {

    protected static final int PARTITIONS = 3;
    protected static final Duration WAIT = Duration.ofSeconds(30);

    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1")).withKraft();

    static {
        KAFKA.start();
        createTopics();
    }

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static void createTopics() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic(MatchingTopics.COMMANDS, PARTITIONS, (short) 1),
                    new NewTopic(MatchingTopics.COMMANDS + ".DLT", 1, (short) 1),
                    new NewTopic(SettlementTopics.REQUESTS, PARTITIONS, (short) 1),
                    new NewTopic(SettlementTopics.REQUESTS + ".DLT", 1, (short) 1)
            )).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토픽 생성 실패", e);
        }
    }

    // DLT 를 처음부터 읽어 조건에 맞는 메시지가 나올 때까지 찾는다. 다른 테스트가 남긴 메시지가 앞에 있을 수 있다.
    protected boolean findDeadLetter(String topic, Predicate<String> matcher) {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            consumer.subscribe(List.of(topic + ".DLT"));
            long deadline = System.currentTimeMillis() + WAIT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matcher.test(new String(record.value(), StandardCharsets.UTF_8))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
