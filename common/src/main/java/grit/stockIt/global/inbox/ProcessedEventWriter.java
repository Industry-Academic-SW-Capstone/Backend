package grit.stockIt.global.inbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

// 받은 이벤트를 소비자마다 한 번만 처리한다. 처리와 같은 트랜잭션에서 기록하므로 처리가 롤백되면 기록도 없어 다시 처리된다.
// 같은 이벤트가 다시 오면(최소 한 번 전달) 0행이 돌아와 건너뛴다.
@Repository
@ConditionalOnProperty(name = "stockit.db.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ProcessedEventWriter {

    private static final String MARK_SQL = """
            INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, now())
            ON CONFLICT (consumer, event_id) DO NOTHING
            """;

    private final EntityManager entityManager;

    public boolean markProcessed(String consumer, String eventId) {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(MARK_SQL)) {
                statement.setString(1, consumer);
                statement.setString(2, eventId);
                return statement.executeUpdate() > 0;
            }
        });
    }
}
