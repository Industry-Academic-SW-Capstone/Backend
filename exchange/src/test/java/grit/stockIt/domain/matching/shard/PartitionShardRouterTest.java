package grit.stockIt.domain.matching.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("파티션 → 샤드 표")
class PartitionShardRouterTest {

    @Test
    @DisplayName("파티션을 적지 않으면 샤드마다 연속 구간을 나눠 맡는다")
    void contiguousDefault() {
        PartitionShardRouter router = new PartitionShardRouter(properties(8, shard(List.of()), shard(List.of())));

        assertThat(router.shardCount()).isEqualTo(2);
        assertThat(IntStream.range(0, 8).map(router::shardOf).toArray()).containsExactly(0, 0, 0, 0, 1, 1, 1, 1);
    }

    @Test
    @DisplayName("적어 준 파티션 목록대로 붙는다")
    void explicitPartitions() {
        PartitionShardRouter router = new PartitionShardRouter(properties(4, shard(List.of(0, 2)), shard(List.of(1, 3))));

        assertThat(IntStream.range(0, 4).map(router::shardOf).toArray()).containsExactly(0, 1, 0, 1);
    }

    @Test
    @DisplayName("어느 샤드에도 붙지 않은 파티션이 있으면 기동하지 않는다")
    void missingPartition_failsStartup() {
        assertThatThrownBy(() -> new PartitionShardRouter(properties(4, shard(List.of(0, 1)), shard(List.of(2)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3");
    }

    @Test
    @DisplayName("두 샤드에 붙은 파티션이 있으면 기동하지 않는다")
    void overlappingPartition_failsStartup() {
        assertThatThrownBy(() -> new PartitionShardRouter(properties(4, shard(List.of(0, 1, 2)), shard(List.of(2, 3)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2");
    }

    @Test
    @DisplayName("파티션 수가 256 을 넘으면 기동하지 않는다(체결 번호의 파티션 자리가 8비트)")
    void partitionCountOver256_fails() {
        assertThatThrownBy(() -> new PartitionShardRouter(properties(257, shard(List.of()))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("샤드가 없으면 기동하지 않는다")
    void noShard_fails() {
        assertThatThrownBy(() -> new PartitionShardRouter(properties(4)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("표 밖의 파티션을 물으면 예외")
    void unknownPartition_throws() {
        PartitionShardRouter router = new PartitionShardRouter(properties(4, shard(List.of()), shard(List.of())));

        assertThatThrownBy(() -> router.shardOf(4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> router.shardOf(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ShardProperties properties(int partitionCount, ShardProperties.Shard... shards) {
        return new ShardProperties(true, partitionCount, List.of(shards));
    }

    private static ShardProperties.Shard shard(List<Integer> partitions) {
        return new ShardProperties.Shard("jdbc:postgresql://localhost/x", "u", "p", 0, partitions);
    }
}
