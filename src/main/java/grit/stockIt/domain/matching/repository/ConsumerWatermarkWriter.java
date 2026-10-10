package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.queue.CommandPosition;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

// 워커가 처리한 위치를 명령 처리와 같은 트랜잭션에 기록한다. 지금 위치가 기록보다 클 때만 올리므로
// 0행이면 이미 반영한 위치다(재전달).
// 같은 파티션을 두 워커가 잠깐 함께 쥐어도(리밸런스) 이 행의 잠금이 둘을 한 줄로 세운다. 한 종목의 명령은 모두
// 한 파티션에 오므로, 이 줄이 곧 종목의 오더북을 바꾸는 유일한 줄이다.
@Repository
@RequiredArgsConstructor
public class ConsumerWatermarkWriter {

    private static final String ADVANCE_SQL = """
            INSERT INTO consumer_watermark (topic, partition_no, last_offset, updated_at)
            VALUES (?, ?, ?, now())
            ON CONFLICT (topic, partition_no) DO UPDATE
               SET last_offset = EXCLUDED.last_offset, updated_at = EXCLUDED.updated_at
             WHERE consumer_watermark.last_offset < EXCLUDED.last_offset
            """;

    private final EntityManager entityManager;

    // 워커는 종목을 한 줄로 처리하므로 문장 하나의 비용이 그대로 종목당 천장을 낮춘다. JPA 쿼리 대신 JDBC 로 바로 보낸다.
    public boolean advance(CommandPosition position) {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(ADVANCE_SQL)) {
                statement.setString(1, position.topic());
                statement.setInt(2, position.partition());
                statement.setLong(3, position.offset());
                return statement.executeUpdate() > 0;
            }
        });
    }
}
