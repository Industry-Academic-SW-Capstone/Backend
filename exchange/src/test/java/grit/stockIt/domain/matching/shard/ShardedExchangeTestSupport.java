package grit.stockIt.domain.matching.shard;

import grit.launch.exchange.ExchangeApplication;
import grit.stockIt.testsupport.ServiceContextTestSupport;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 샤드 둘(같은 컨테이너의 빈 DB 둘)로 거래소를 띄우는 테스트의 기반. 파티션 4개를 샤드 0 = 0~1, 샤드 1 = 2~3 으로 나눈다.
// 테스트 클래스마다 다른 DB 이름을 넘긴다. 같은 이름이면 다음 컨텍스트가 DB 를 지우고 만들어 캐시된 앞 컨텍스트의 커넥션을 끊는다.
@SpringBootTest(classes = ExchangeApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false",
        "stockit.exchange.sharding.enabled=true",
        "stockit.exchange.sharding.partition-count=" + ShardTestKafka.COMMAND_PARTITIONS
})
@ActiveProfiles("exchange")
abstract class ShardedExchangeTestSupport extends ServiceContextTestSupport {

    protected static final int SHARDS = 2;

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", ShardTestKafka::bootstrapServers);
    }

    protected static void registerShards(DynamicPropertyRegistry registry, String shardA, String shardB) {
        String urlA = freshDatabaseUrl(shardA);
        String urlB = freshDatabaseUrl(shardB);
        registry.add("stockit.exchange.sharding.shards[0].url", () -> urlA);
        registry.add("stockit.exchange.sharding.shards[1].url", () -> urlB);
        for (int shard = 0; shard < SHARDS; shard++) {
            registry.add("stockit.exchange.sharding.shards[" + shard + "].username", POSTGRES::getUsername);
            registry.add("stockit.exchange.sharding.shards[" + shard + "].password", POSTGRES::getPassword);
            registry.add("stockit.exchange.sharding.shards[" + shard + "].pool-size", () -> 3);
        }
    }
}
