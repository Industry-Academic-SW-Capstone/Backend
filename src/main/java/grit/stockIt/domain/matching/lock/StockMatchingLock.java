package grit.stockIt.domain.matching.lock;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.SQLException;

// 종목별 직렬화 락. 이 종목의 오더북을 바꾸는 경로는 모두 이 락을 잡아야 한다.
// 트랜잭션 스코프라 커밋·롤백 시 자동으로 풀린다.
@Component
@RequiredArgsConstructor
public class StockMatchingLock {

    // advisory 락 네임스페이스. 다른 용도의 advisory 락과 키가 겹치지 않게 한다.
    private static final int LOCK_CLASS_ID = 1001;

    // 큐 워커용. 문장 셋을 드라이버가 한 왕복에 보내고 서버가 차례로 실행한다 — 락 타임아웃이 락보다 먼저,
    // 위치 기록이 락보다 뒤에 걸린다. 위치 행의 조건부 갱신이 재전달을 거르고, 행 잠금이 같은 위치를 쥔 두 워커를 줄 세운다.
    private static final String ACQUIRE_AND_ADVANCE_SQL = """
            SELECT set_config('lock_timeout', ?, true);
            SELECT pg_advisory_xact_lock(?, hashtext(?));
            INSERT INTO consumer_watermark (topic, partition_no, last_offset, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (topic, partition_no) DO UPDATE
               SET last_offset = EXCLUDED.last_offset, updated_at = EXCLUDED.updated_at
             WHERE consumer_watermark.last_offset < EXCLUDED.last_offset
            """;

    private final EntityManager entityManager;

    @Value("${matching.lock.timeout:10s}")
    private String defaultTimeout;

    public void acquire(String stockCode) {
        acquire(stockCode, defaultTimeout);
    }

    // SET LOCAL 은 바인드 파라미터를 받지 못해 set_config 를 쓴다. 세 번째 인자가 트랜잭션 로컬 여부다.
    // advisory 락은 기본이 무한 대기라 타임아웃을 반드시 걸어야 한다.
    public void acquire(String stockCode, String timeout) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", timeout)
                .getSingleResult();

        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:classId, hashtext(:stockCode))")
                .setParameter("classId", LOCK_CLASS_ID)
                .setParameter("stockCode", stockCode)
                .getSingleResult();
    }

    // 워커는 종목을 한 줄로 처리하므로 왕복 하나가 그대로 종목당 천장을 낮춘다. 그래서 셋을 한 번에 보낸다.
    // true 면 처음 보는 위치, false 면 이미 반영한 위치(재전달)다.
    public boolean acquireAndAdvance(String stockCode, String topic, int partition, long offset) {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ACQUIRE_AND_ADVANCE_SQL)) {
                statement.setString(1, defaultTimeout);
                statement.setInt(2, LOCK_CLASS_ID);
                statement.setString(3, stockCode);
                statement.setString(4, topic);
                statement.setInt(5, partition);
                statement.setLong(6, offset);
                statement.execute();
                return advancedRows(statement) > 0;
            }
        });
    }

    // 결과는 set_config 행, 락 행, INSERT 갱신 수 순서로 온다. 앞의 두 SELECT 를 건너뛴다.
    private int advancedRows(PreparedStatement statement) throws SQLException {
        statement.getMoreResults();
        statement.getMoreResults();
        int updateCount = statement.getUpdateCount();
        if (updateCount < 0) {
            throw new IllegalStateException("워커 위치 기록 결과를 받지 못했습니다.");
        }
        return updateCount;
    }
}
