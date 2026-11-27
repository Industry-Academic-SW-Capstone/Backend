package grit.stockIt.domain.matching.shard;

import grit.stockIt.domain.matching.queue.MatchingTopics;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("샤드 파티션 수와 matching.commands 실제 파티션 수 대조")
class ShardPartitionCountVerifierTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaAdminConfig.class, ShardPartitionCountVerifier.class)
            .withPropertyValues("stockit.exchange.sharding.enabled=true");

    @Test
    @DisplayName("같으면 통과한다")
    void sameCount_passes() {
        try (AdminClient admin = ShardTestKafka.admin()) {
            assertThatCode(() -> ShardPartitionCountVerifier.requirePartitionCount(admin, MatchingTopics.COMMANDS,
                    ShardTestKafka.COMMAND_PARTITIONS, TIMEOUT)).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 5})
    @DisplayName("다르면 실패한다")
    void differentCount_throws(int configured) {
        try (AdminClient admin = ShardTestKafka.admin()) {
            assertThatThrownBy(() -> ShardPartitionCountVerifier.requirePartitionCount(admin, MatchingTopics.COMMANDS,
                    configured, TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("partition-count(" + configured + ")");
        }
    }

    @Test
    @DisplayName("토픽이 없으면 실패한다")
    void missingTopic_throws() {
        try (AdminClient admin = ShardTestKafka.admin()) {
            assertThatThrownBy(() -> ShardPartitionCountVerifier.requirePartitionCount(admin, "no.such.topic",
                    ShardTestKafka.COMMAND_PARTITIONS, TIMEOUT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("토픽이 없어");
        }
    }

    @Test
    @DisplayName("설정이 실제와 다르면 기동하지 않는다")
    void mismatch_failsStartup() {
        runner.withPropertyValues("stockit.exchange.sharding.partition-count=5").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("partition-count(5)");
        });
    }

    @Test
    @DisplayName("설정이 실제와 같으면 뜬다")
    void match_starts() {
        runner.withPropertyValues("stockit.exchange.sharding.partition-count=" + ShardTestKafka.COMMAND_PARTITIONS)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ShardProperties.class)
    static class KafkaAdminConfig {

        @Bean
        KafkaAdmin kafkaAdmin() {
            return new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, ShardTestKafka.bootstrapServers()));
        }
    }
}
