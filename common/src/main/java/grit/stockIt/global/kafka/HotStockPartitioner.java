package grit.stockIt.global.kafka;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.Utils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import grit.stockIt.domain.matching.queue.MatchingTopics;

// 모든 서비스가 같은 표를 써야 한 종목이 한 파티션에만 쌓인다.
public class HotStockPartitioner implements Partitioner {

    public static final String HOT_STOCKS_CONFIG = "matching.hot-stocks";

    private Map<String, Integer> table = Map.of();

    @Override
    public void configure(Map<String, ?> configs) {
        Object value = configs.get(HOT_STOCKS_CONFIG);
        table = parse(value == null ? "" : value.toString());
    }

    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        int count = partitions.size();
        if (keyBytes == null) {
            return nullKeyPartition(cluster, topic, partitions);
        }
        if (MatchingTopics.COMMANDS.equals(topic)) {
            // DLT 재전송은 byte[] 키로 보낸다. 키는 StringSerializer 의 UTF-8 이라 되돌려 읽는다.
            String stockCode = key instanceof String text ? text : new String(keyBytes, StandardCharsets.UTF_8);
            Integer pinned = table.get(stockCode);
            if (pinned != null) {
                if (pinned >= count) {
                    throw new IllegalArgumentException(
                            "바쁜 종목 " + stockCode + " 의 파티션 " + pinned + " 이 토픽 파티션 수 " + count + " 를 넘는다");
                }
                return pinned;
            }
        }
        return Utils.toPositive(Utils.murmur2(keyBytes)) % count;
    }

    // 키 없는 메시지는 쓸 수 있는 파티션 중 무작위로 보낸다. 음수를 돌려주면 KafkaProducer 가 예외를 낸다.
    private static int nullKeyPartition(Cluster cluster, String topic, List<PartitionInfo> partitions) {
        List<PartitionInfo> available = cluster.availablePartitionsForTopic(topic);
        List<PartitionInfo> pool = available.isEmpty() ? partitions : available;
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size())).partition();
    }

    @Override
    public void close() {
    }

    public static Map<String, Integer> parse(String spec) {
        Map<String, Integer> result = new HashMap<>();
        if (spec == null || spec.isBlank()) {
            return result;
        }
        for (String entry : spec.split(",")) {
            String[] parts = entry.trim().split(":");
            if (parts.length != 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("바쁜 종목 표 형식이 아니다(종목:파티션): " + entry);
            }
            int partition;
            try {
                partition = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("바쁜 종목 표의 파티션이 숫자가 아니다: " + entry, e);
            }
            if (partition < 0) {
                throw new IllegalArgumentException("바쁜 종목 표의 파티션이 음수다: " + entry);
            }
            if (result.put(parts[0].trim(), partition) != null) {
                throw new IllegalArgumentException("바쁜 종목 표에 같은 종목이 두 번 있다: " + entry);
            }
        }
        return result;
    }
}
