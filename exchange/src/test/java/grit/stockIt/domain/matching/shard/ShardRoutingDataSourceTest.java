package grit.stockIt.domain.matching.shard;

import com.zaxxer.hikari.HikariDataSource;
import grit.stockIt.global.shard.ShardContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("샤드 라우팅 DataSource")
class ShardRoutingDataSourceTest {

    @Test
    @DisplayName("샤드를 정하지 않으면 빈 초기화 중에는 샤드 0, 수명주기가 시작되면 예외")
    void unscoped_shardZeroUntilStarted() throws Exception {
        DataSource shard0 = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(shard0.getConnection()).thenReturn(connection);
        ShardRoutingDataSource routing = new ShardRoutingDataSource(List.of(shard0, mock(DataSource.class)));

        assertThat(routing.getConnection()).isSameAs(connection);

        routing.start();
        assertThatThrownBy(routing::getConnection)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ShardContext");
    }

    @Test
    @DisplayName("없는 샤드 번호는 기본 샤드로 가지 않고 예외")
    void unknownShard_throws() {
        ShardRoutingDataSource routing = new ShardRoutingDataSource(List.of(mock(DataSource.class)));

        assertThatThrownBy(() -> ShardContext.call(1, () -> {
            try {
                return routing.getConnection();
            } catch (SQLException e) {
                throw new IllegalArgumentException(e);
            }
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("닫으면 샤드 풀도 모두 닫는다")
    void destroy_closesPools() throws Exception {
        HikariDataSource shard0 = new HikariDataSource();
        HikariDataSource shard1 = new HikariDataSource();
        ShardRoutingDataSource routing = new ShardRoutingDataSource(List.of(shard0, shard1));

        routing.destroy();

        assertThat(shard0.isClosed()).isTrue();
        assertThat(shard1.isClosed()).isTrue();
    }
}
