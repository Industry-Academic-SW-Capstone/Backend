package grit.stockIt.global.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.kafka.support.JacksonUtils;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

// 보낼 메시지를 업무 변경과 같은 트랜잭션에 적는다. 함께 커밋되거나 함께 롤백되므로 "커밋됐는데 안 보냄"도
// "보냈는데 롤백됨"도 없다. 실제 전송은 OutboxRelay 가 한다. 호출하는 쪽에 트랜잭션이 열려 있어야 한다.
@Repository
@ConditionalOnProperty(name = "stockit.db.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxWriter {

    private static final String INSERT_SQL =
            "INSERT INTO outbox_message (topic, message_key, payload, created_at) VALUES (?, ?, ?, now())";

    // Kafka JsonSerializer 의 기본 매퍼와 같다. 웹 응답용 매퍼(SNAKE_CASE)로 적으면 받는 쪽이 필드를 읽지 못한다.
    private static final ObjectMapper MAPPER = JacksonUtils.enhancedObjectMapper();

    private final EntityManager entityManager;

    // 체결 워커의 트랜잭션 안에서 불린다. 문장 하나의 비용이 종목당 천장을 낮추므로 JDBC 배치 한 번으로 넣는다.
    public void append(List<OutboxRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
                for (OutboxRecord record : records) {
                    statement.setString(1, record.topic());
                    statement.setString(2, record.key());
                    statement.setString(3, toJson(record.payload()));
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        });
    }

    private static String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("아웃박스 메시지를 직렬화할 수 없습니다. " + payload, e);
        }
    }
}
