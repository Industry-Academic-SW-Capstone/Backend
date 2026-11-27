package grit.stockIt.domain.matching.shard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("샤드 DataSource 설정")
class ShardDataSourceConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ShardDataSourceConfig.class)
            .withPropertyValues(
                    "stockit.exchange.sharding.enabled=true",
                    "stockit.exchange.sharding.partition-count=4",
                    "stockit.exchange.sharding.shards[0].url=jdbc:postgresql://localhost:1/none");

    // 요청 범위 EntityManager 가 샤드 0 커넥션을 쥔 채 ShardContext 로 바꾼 샤드 쿼리까지 샤드 0 으로 보낸다.
    @ParameterizedTest
    @ValueSource(strings = {"", "spring.jpa.open-in-view=true"})
    @DisplayName("open-in-view 를 false 로 끄지 않으면 기동하지 않는다")
    void openInViewNotDisabled_failsStartup(String openInView) {
        runner.withPropertyValues(openInView).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.jpa.open-in-view");
        });
    }
}
