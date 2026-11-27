package grit.stockIt.domain.matching.shard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// 거래소 샤드 DB 목록과 matching.commands 파티션 → 샤드 표. 샤드마다 partitions 를 비우면 샤드 순서대로 연속 구간을 나눠 맡는다.
@ConfigurationProperties("stockit.exchange.sharding")
public record ShardProperties(boolean enabled, int partitionCount, List<Shard> shards) {

    public ShardProperties {
        shards = shards == null ? List.of() : List.copyOf(shards);
    }

    // poolSize 가 0 이면 spring.datasource.hikari 의 값(없으면 Hikari 기본값)을 쓴다.
    public record Shard(String url, String username, String password, int poolSize, List<Integer> partitions) {

        public Shard {
            partitions = partitions == null ? List.of() : List.copyOf(partitions);
        }
    }
}
