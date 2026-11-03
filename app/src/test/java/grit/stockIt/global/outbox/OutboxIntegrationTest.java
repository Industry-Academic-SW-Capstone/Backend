package grit.stockIt.global.outbox;

import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 브로커 없이 아웃박스의 두 약속을 본다 — 업무가 롤백되면 메시지도 없고, 보내지 못한 메시지는 지우지 않는다.
// 로컬에 Kafka 가 떠 있어도 전송이 실패하도록 닿지 않는 주소를 쓴다.
@TestPropertySource(properties = "spring.kafka.bootstrap-servers=localhost:1")
@DisplayName("아웃박스 — 롤백과 전송 실패 (통합 테스트)")
class OutboxIntegrationTest extends IntegrationTestSupport {

    private static final String TOPIC = "outbox.test";

    @Autowired private OutboxWriter outboxWriter;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clear() {
        jdbcTemplate.update("DELETE FROM outbox_message");
    }

    @Test
    @DisplayName("업무 트랜잭션이 롤백되면 아웃박스에도 남지 않는다")
    void rolledBack_leavesNoMessage() {
        transactionTemplate.executeWithoutResult(status -> {
            outboxWriter.append(List.of(new OutboxRecord(TOPIC, "1", Map.of("value", 1))));
            status.setRollbackOnly();
        });

        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("커밋되면 적은 순서대로 남고, 보내지 못하면 지우지 않는다")
    void sendFailure_keepsMessages() {
        transactionTemplate.executeWithoutResult(status -> outboxWriter.append(List.of(
                new OutboxRecord(TOPIC, "1", Map.of("value", 1)),
                new OutboxRecord(TOPIC, "1", Map.of("value", 2)))));

        assertThat(jdbcTemplate.queryForList("SELECT payload FROM outbox_message ORDER BY id", String.class))
                .containsExactly("{\"value\":1}", "{\"value\":2}");

        assertThatThrownBy(outboxRelay::relayOnce).isInstanceOf(RuntimeException.class);
        assertThat(count()).isEqualTo(2);
    }

    private int count() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_message", Integer.class);
        return count == null ? 0 : count;
    }
}
