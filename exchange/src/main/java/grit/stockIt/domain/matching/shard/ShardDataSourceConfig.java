package grit.stockIt.domain.matching.shard;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import grit.stockIt.global.config.SearchPathConfig;
import grit.stockIt.global.shard.ShardRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// 샤드 설정이 있으면 Spring Boot 기본 DataSource 대신 샤드마다 풀을 만들고 그 위에 라우팅 DataSource 를 둔다.
// Hibernate 는 기동 때 샤드 0 에만 스키마를 만들거나 검사하므로, 샤드마다 거래소 스키마 스크립트를 먼저 돌린다.
@Configuration
@ConditionalOnProperty(name = "stockit.exchange.sharding.enabled", havingValue = "true")
@EnableConfigurationProperties(ShardProperties.class)
public class ShardDataSourceConfig {

    private static final String SCHEMA_SCRIPT = "db/shard/exchange-schema.sql";
    // 빈 샤드에 서버 여럿이 함께 뜨면 CREATE ... IF NOT EXISTS 끼리도 pg_type 유일 위반으로 부딪혀, 한 서버씩 돌린다.
    private static final long SCHEMA_LOCK_KEY = 0x53544F434B4954L;

    @Bean
    public ShardRouter shardRouter(ShardProperties properties) {
        return new PartitionShardRouter(properties);
    }

    @Bean
    @Primary
    public ShardRoutingDataSource dataSource(ShardProperties properties, ShardRouter shardRouter, Environment environment,
                                             @Value("${stockit.db.search-path:}") String searchPath,
                                             ObjectProvider<MeterRegistry> meterRegistry) {
        requireOpenInViewDisabled(environment);
        Optional<String> initSql = SearchPathConfig.connectionInitSql(searchPath);
        List<HikariDataSource> pools = new ArrayList<>();
        try {
            for (int shard = 0; shard < shardRouter.shardCount(); shard++) {
                HikariDataSource pool = createPool(shard, properties.shards().get(shard), environment, initSql,
                        meterRegistry.getIfAvailable());
                pools.add(pool);
                createSchema(pool);
            }
        } catch (RuntimeException e) {
            pools.forEach(HikariDataSource::close);
            throw e;
        }
        return new ShardRoutingDataSource(pools);
    }

    private static void createSchema(HikariDataSource pool) {
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + SCHEMA_LOCK_KEY + ")");
            try {
                new ResourceDatabasePopulator(new ClassPathResource(SCHEMA_SCRIPT)).populate(connection);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(" + SCHEMA_LOCK_KEY + ")");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("샤드 스키마를 만들지 못했습니다. pool=" + pool.getPoolName(), e);
        }
    }

    // 요청 범위 EntityManager 가 처음 받은 샤드의 커넥션을 요청 끝까지 쥐어, 뒤에 ShardContext 로 바꾼 샤드의 쿼리도 그리로 간다.
    private static void requireOpenInViewDisabled(Environment environment) {
        if (!"false".equalsIgnoreCase(environment.getProperty("spring.jpa.open-in-view"))) {
            throw new IllegalStateException("거래소 샤딩(stockit.exchange.sharding.enabled=true)에는 spring.jpa.open-in-view=false 가 필요합니다.");
        }
    }

    private static HikariDataSource createPool(int shard, ShardProperties.Shard config, Environment environment,
                                               Optional<String> initSql, MeterRegistry meterRegistry) {
        HikariDataSource pool = new HikariDataSource();
        // 타임아웃 같은 공통 풀 설정은 기본 DataSource 와 같은 키(spring.datasource.hikari.*)에서 받는다.
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
        pool.setPoolName("exchange-shard-" + shard);
        pool.setJdbcUrl(config.url());
        pool.setUsername(config.username());
        pool.setPassword(config.password());
        if (config.poolSize() > 0) {
            pool.setMaximumPoolSize(config.poolSize());
        }
        initSql.ifPresent(pool::setConnectionInitSql);
        if (meterRegistry != null) {
            pool.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        }
        return pool;
    }
}
