package grit.stockIt.global.shard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 샤드 설정이 없을 때: 모든 파티션이 기본 DataSource 하나(샤드 0)에 붙는다.
@Component
@ConditionalOnProperty(name = "stockit.exchange.sharding.enabled", havingValue = "false", matchIfMissing = true)
public class SingleShardRouter implements ShardRouter {

    @Override
    public int shardOf(int partition) {
        return 0;
    }

    @Override
    public int shardCount() {
        return 1;
    }
}
