package grit.stockIt.domain.matching.shard;

import com.zaxxer.hikari.HikariDataSource;
import grit.stockIt.global.shard.ShardContext;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShardRoutingDataSource extends AbstractRoutingDataSource implements SmartLifecycle, DisposableBean {

    private volatile boolean strict;
    private volatile boolean running;

    public ShardRoutingDataSource(List<? extends DataSource> shards) {
        Map<Object, Object> targets = new HashMap<>();
        for (int shard = 0; shard < shards.size(); shard++) {
            targets.put(shard, shards.get(shard));
        }
        setTargetDataSources(targets);
        // 없는 샤드 번호가 기본 샤드로 조용히 가지 않게 한다.
        setLenientFallback(false);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (ShardContext.current().isPresent()) {
            return ShardContext.current().getAsInt();
        }
        // 빈 초기화(Hibernate 기동·지표 등록)는 샤드 없이 커넥션을 받는다. 스키마는 샤드마다 같으므로 샤드 0 으로 충분하다.
        if (!strict) {
            return 0;
        }
        throw new IllegalStateException("샤드를 정하지 않고 거래소 DB 커넥션을 받았습니다. ShardContext 로 샤드를 먼저 정하세요.");
    }

    // 가장 먼저 시작해, Kafka 리스너 컨테이너·스케줄러·웹 서버가 시작될 때는 이미 엄격하다.
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    @Override
    public void start() {
        strict = true;
        running = true;
    }

    // 멈춘 뒤에도 엄격하게 둔다. 종료 중의 샤드 없는 접근도 잡는다.
    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // 풀은 빈이 아니라 컨텍스트가 닫아 주지 않는다.
    @Override
    public void destroy() {
        getResolvedDataSources().values().forEach(shard -> {
            if (shard instanceof HikariDataSource pool) {
                pool.close();
            }
        });
    }
}
