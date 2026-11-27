package grit.stockIt.global.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HotStockPartitionerTest {

    private static final String COMMANDS = "matching.commands";
    private static final String REPORTS = "exchange.reports";

    private final Cluster cluster = cluster();

    private static Cluster cluster() {
        Node node = new Node(0, "localhost", 9092);
        List<PartitionInfo> infos = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            infos.add(new PartitionInfo(COMMANDS, i, node, new Node[]{node}, new Node[]{node}));
        }
        for (int i = 0; i < 24; i++) {
            infos.add(new PartitionInfo(REPORTS, i, node, new Node[]{node}, new Node[]{node}));
        }
        return new Cluster("test", List.of(node), infos, Set.of(), Set.of());
    }

    private HotStockPartitioner partitioner(String table) {
        HotStockPartitioner partitioner = new HotStockPartitioner();
        partitioner.configure(Map.of(HotStockPartitioner.HOT_STOCKS_CONFIG, table));
        return partitioner;
    }

    private int partition(HotStockPartitioner partitioner, String topic, String key) {
        byte[] bytes = key == null ? null : key.getBytes(StandardCharsets.UTF_8);
        return partitioner.partition(topic, key, bytes, null, null, cluster);
    }

    @Test
    void hotStock_goesToTablePartition() {
        assertThat(partition(partitioner("005930:3"), COMMANDS, "005930")).isEqualTo(3);
    }

    @Test
    void otherStock_matchesKafkaDefault() {
        HotStockPartitioner partitioner = partitioner("005930:3");
        for (int i = 0; i < 20; i++) {
            String key = String.format("%06d", 100000 + i * 37);
            int expected = Utils.toPositive(Utils.murmur2(key.getBytes(StandardCharsets.UTF_8))) % 48;
            assertThat(partition(partitioner, COMMANDS, key)).isEqualTo(expected);
        }
    }

    @Test
    void otherTopic_ignoresTable() {
        int expected = Utils.toPositive(Utils.murmur2("005930".getBytes(StandardCharsets.UTF_8))) % 24;
        assertThat(partition(partitioner("005930:3"), REPORTS, "005930")).isEqualTo(expected);
    }

    @Test
    void tablePartitionOutOfRange_throws() {
        HotStockPartitioner partitioner = partitioner("005930:48");
        assertThatThrownBy(() -> partition(partitioner, COMMANDS, "005930"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullKey_returnsValidPartition() {
        HotStockPartitioner partitioner = partitioner("005930:3");
        for (int i = 0; i < 50; i++) {
            assertThat(partition(partitioner, COMMANDS, null)).isBetween(0, 47);
        }
    }

    @Test
    void parse_rejectsMalformed() {
        for (String bad : List.of("005930", "005930:x", "005930:-1", "005930:3,005930:4")) {
            assertThatThrownBy(() -> HotStockPartitioner.parse(bad))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(HotStockPartitioner.parse("005930:3,000660:7"))
                .containsEntry("005930", 3).containsEntry("000660", 7);
    }

    @Test
    void config_absentWhenEmpty() {
        new ApplicationContextRunner()
                .withUserConfiguration(HotStockPartitionerConfig.class)
                .withPropertyValues("matching.hot-stocks=")
                .run(context -> assertThat(context).doesNotHaveBean(HotStockPartitionerConfig.class));
    }

    @Test
    void config_presentWhenTableGiven() {
        new ApplicationContextRunner()
                .withUserConfiguration(HotStockPartitionerConfig.class)
                .withPropertyValues("matching.hot-stocks=005930:3")
                .run(context -> assertThat(context).hasSingleBean(HotStockPartitionerConfig.class));
    }

    @Test
    void customizer_putsPartitionerAndTableIntoProducerConfig() {
        new ApplicationContextRunner()
                .withUserConfiguration(HotStockPartitionerConfig.class)
                .withPropertyValues("matching.hot-stocks=005930:3")
                .run(context -> {
                    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(new HashMap<>());
                    context.getBean(DefaultKafkaProducerFactoryCustomizer.class).customize(factory);
                    assertThat(factory.getConfigurationProperties())
                            .containsEntry(ProducerConfig.PARTITIONER_CLASS_CONFIG, HotStockPartitioner.class)
                            .containsEntry(HotStockPartitioner.HOT_STOCKS_CONFIG, "005930:3");
                });
    }
}
