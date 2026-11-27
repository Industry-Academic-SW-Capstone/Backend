package grit.stockIt.domain.matching.shard;

import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.domain.matching.queue.MatchingTopics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

// 샤드 테스트가 함께 쓰는 브로커. 기동 때 matching.commands 파티션 수를 확인하므로 샤드 설정(4)과 같게 만든다.
final class ShardTestKafka {

    static final int COMMAND_PARTITIONS = 4;

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1")).withKraft();

    static {
        KAFKA.start();
        createTopics();
    }

    private ShardTestKafka() {
    }

    static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers()));
    }

    private static void createTopics() {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(
                    new NewTopic(MatchingTopics.COMMANDS, COMMAND_PARTITIONS, (short) 1),
                    new NewTopic(MatchingTopics.COMMANDS + ".DLT", 1, (short) 1),
                    new NewTopic(ExchangeTopics.REPORTS, 3, (short) 1),
                    new NewTopic(ExchangeTopics.REPORTS + ".DLT", 1, (short) 1)
            )).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토픽 생성 실패", e);
        }
    }
}
