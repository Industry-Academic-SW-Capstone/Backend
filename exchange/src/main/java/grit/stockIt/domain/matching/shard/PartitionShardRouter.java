package grit.stockIt.domain.matching.shard;

import grit.stockIt.global.shard.ShardRouter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

public class PartitionShardRouter implements ShardRouter {

    // 체결 번호(FillIdGenerator)의 파티션 자리가 8비트다.
    private static final int MAX_PARTITIONS = 256;
    private static final int UNASSIGNED = -1;

    private final int[] shardByPartition;
    private final int shardCount;

    public PartitionShardRouter(ShardProperties properties) {
        List<ShardProperties.Shard> shards = properties.shards();
        int partitionCount = properties.partitionCount();
        if (shards.isEmpty()) {
            throw new IllegalStateException("stockit.exchange.sharding.shards 가 비어 있습니다.");
        }
        if (partitionCount <= 0 || partitionCount > MAX_PARTITIONS) {
            throw new IllegalStateException("stockit.exchange.sharding.partition-count 는 1 ~ " + MAX_PARTITIONS
                    + " 이어야 합니다: " + partitionCount);
        }
        this.shardCount = shards.size();
        this.shardByPartition = shards.stream().allMatch(shard -> shard.partitions().isEmpty())
                ? contiguous(partitionCount, shardCount)
                : explicit(partitionCount, shards);
    }

    @Override
    public int shardOf(int partition) {
        if (partition < 0 || partition >= shardByPartition.length) {
            throw new IllegalArgumentException("파티션 → 샤드 표에 없는 파티션입니다: " + partition);
        }
        return shardByPartition[partition];
    }

    @Override
    public int shardCount() {
        return shardCount;
    }

    private static int[] contiguous(int partitionCount, int shardCount) {
        if (partitionCount < shardCount) {
            throw new IllegalStateException("파티션(" + partitionCount + ")보다 샤드(" + shardCount + ")가 많습니다.");
        }
        return IntStream.range(0, partitionCount).map(partition -> partition * shardCount / partitionCount).toArray();
    }

    private static int[] explicit(int partitionCount, List<ShardProperties.Shard> shards) {
        int[] table = new int[partitionCount];
        Arrays.fill(table, UNASSIGNED);
        for (int shard = 0; shard < shards.size(); shard++) {
            List<Integer> partitions = shards.get(shard).partitions();
            if (partitions.isEmpty()) {
                throw new IllegalStateException("샤드 " + shard + " 의 partitions 가 비어 있습니다. 한 샤드라도 적으면 모두 적어야 합니다.");
            }
            for (int partition : partitions) {
                if (partition < 0 || partition >= partitionCount) {
                    throw new IllegalStateException("샤드 " + shard + " 의 파티션 " + partition + " 이 0 ~ "
                            + (partitionCount - 1) + " 밖입니다.");
                }
                if (table[partition] != UNASSIGNED) {
                    throw new IllegalStateException("파티션 " + partition + " 이 샤드 " + table[partition] + " 과 "
                            + shard + " 에 겹칩니다.");
                }
                table[partition] = shard;
            }
        }
        int[] missing = IntStream.range(0, partitionCount).filter(partition -> table[partition] == UNASSIGNED).toArray();
        if (missing.length > 0) {
            throw new IllegalStateException("어느 샤드에도 붙지 않은 파티션이 있습니다: " + Arrays.toString(missing));
        }
        return table;
    }
}
