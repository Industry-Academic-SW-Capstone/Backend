package grit.stockIt.domain.matching.shard;

import grit.stockIt.domain.matching.queue.MatchingTopics;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 설정이 실제보다 크면 연속 구간이 틀어져 샤드가 놀고, 작으면 표에 없는 파티션의 명령이 DLT 로 간다.
// Kafka 리스너와 릴레이가 시작되기 전에 돈다.
@Component
@ConditionalOnProperty(name = "stockit.exchange.sharding.enabled", havingValue = "true")
@RequiredArgsConstructor
public class ShardPartitionCountVerifier implements SmartInitializingSingleton {

    private final KafkaAdmin kafkaAdmin;
    private final ShardProperties properties;

    @Override
    public void afterSingletonsInstantiated() {
        Duration timeout = Duration.ofSeconds(kafkaAdmin.getOperationTimeout());
        AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        try {
            requirePartitionCount(admin, MatchingTopics.COMMANDS, properties.partitionCount(), timeout);
        } finally {
            // 브로커에 닿지 않으면 기본 close 는 남은 요청을 오래 기다린다.
            admin.close(timeout);
        }
    }

    static void requirePartitionCount(AdminClient admin, String topic, int expected, Duration timeout) {
        int actual = describe(admin, topic, timeout).partitions().size();
        if (actual != expected) {
            throw new IllegalStateException("stockit.exchange.sharding.partition-count(" + expected + ") 가 "
                    + topic + " 의 실제 파티션 수(" + actual + ")와 다릅니다.");
        }
    }

    private static TopicDescription describe(AdminClient admin, String topic, Duration timeout) {
        try {
            return admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS).get(topic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(topic + " 파티션 수 확인 중 중단됐습니다.", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                throw new IllegalStateException(topic + " 토픽이 없어 샤드 파티션 수를 확인할 수 없습니다.", e);
            }
            throw new IllegalStateException(topic + " 파티션 수를 확인하지 못했습니다.", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException(topic + " 파티션 수 확인이 " + timeout + " 안에 끝나지 않았습니다.", e);
        }
    }
}
