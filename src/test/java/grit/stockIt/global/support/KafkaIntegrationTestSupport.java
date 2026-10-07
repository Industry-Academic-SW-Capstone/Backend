package grit.stockIt.global.support;

import grit.stockIt.domain.matching.queue.MatchingTopics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

// 체결 큐를 켠 통합 테스트. 브로커는 JVM 당 한 번 띄우고, 토픽은 운영처럼 미리 만든다(자동 생성에 기대지 않는다).
@TestPropertySource(properties = {
        "matching.queue.enabled=true",
        "matching.queue.fill-concurrency=" + KafkaIntegrationTestSupport.PARTITIONS
})
public abstract class KafkaIntegrationTestSupport extends IntegrationTestSupport {

    protected static final int PARTITIONS = 3;

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
                    new NewTopic(MatchingTopics.COMMANDS + ".DLT", 1, (short) 1)
            )).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토픽 생성 실패", e);
        }
    }
}
