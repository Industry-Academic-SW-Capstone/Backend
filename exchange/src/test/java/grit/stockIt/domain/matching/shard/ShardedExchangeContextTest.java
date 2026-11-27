package grit.stockIt.domain.matching.shard;

import grit.stockIt.global.shard.ShardContext;
import grit.stockIt.global.shard.ShardRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// validate 라 샤드 스키마 스크립트가 엔티티와 어긋나면 뜨지 않는다.
@DisplayName("샤드 둘로 거래소 기동")
class ShardedExchangeContextTest extends ShardedExchangeTestSupport {

    private static final List<String> EXCHANGE_TABLES =
            List.of("exchange_order", "exchange_fill", "consumer_watermark", "outbox_message", "processed_event");

    @DynamicPropertySource
    static void configureShards(DynamicPropertyRegistry registry) {
        registerShards(registry, "shard_a", "shard_b");
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ShardRouter shardRouter;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    @DisplayName("빈 샤드 DB 에 대고 뜨고, 라우팅 DataSource 와 파티션 표를 쓴다")
    void bootsAgainstFreshShards() {
        assertThat(dataSource).isInstanceOf(ShardRoutingDataSource.class);
        assertThat(shardRouter).isInstanceOf(PartitionShardRouter.class);
        assertThat(shardRouter.shardCount()).isEqualTo(2);
        assertThat(shardRouter.shardOf(1)).isZero();
        assertThat(shardRouter.shardOf(2)).isEqualTo(1);
    }

    @Test
    @DisplayName("샤드마다 거래소 표가 exchange schema 에 있다")
    void eachShardHasExchangeTables() {
        for (int shard = 0; shard < SHARDS; shard++) {
            ShardContext.run(shard, () -> EXCHANGE_TABLES.forEach(table ->
                    assertThat(jdbcTemplate.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                            "exchange." + table)).as(table).isTrue()));
        }
    }

    @Test
    @DisplayName("기동이 끝난 뒤 샤드를 정하지 않고 커넥션을 받으면 예외")
    void unscopedConnectionAfterStart_throws() {
        assertThatThrownBy(() -> dataSource.getConnection())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ShardContext");
    }

    @Test
    @DisplayName("정한 샤드의 DB 로 가고 search_path 는 exchange 다")
    void scopedConnection_reachesThatShard() {
        assertThat(ShardContext.call(0, () -> jdbcTemplate.queryForObject("SELECT current_database()", String.class)))
                .isEqualTo("shard_a");
        assertThat(ShardContext.call(1, () -> jdbcTemplate.queryForObject("SELECT current_database()", String.class)))
                .isEqualTo("shard_b");
        assertThat(ShardContext.call(1, () -> jdbcTemplate.queryForObject("SELECT current_schema()", String.class)))
                .isEqualTo("exchange");
    }

    @Test
    @DisplayName("DB 상태 확인이 샤드마다 UP 이다")
    void health_isUpWithShards() {
        HealthComponent db = healthEndpoint.healthForPath("db");
        assertThat(db).isNotNull();
        assertThat(db.getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.healthForPath("db", "0").getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.healthForPath("db", "1").getStatus()).isEqualTo(Status.UP);
    }
}
