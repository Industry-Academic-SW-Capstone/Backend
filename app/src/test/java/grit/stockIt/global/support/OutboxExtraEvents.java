package grit.stockIt.global.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.contract.ExtraEvent;
import grit.stockIt.contract.ExtraTopics;
import grit.stockIt.domain.member.queue.ExtraEventConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.JacksonUtils;

import java.util.List;
import java.util.Map;

// 릴레이가 없는 테스트에서 부가가 아웃박스에 적은 이벤트(보상 지급 요청·티어)를 꺼낸다. 릴레이처럼 id 순서로 읽고 꺼낸 행은 지운다.
// 테이블은 다른 테스트와 함께 쓰므로 그 회원(메시지 키)의 이벤트만 꺼낸다.
public final class OutboxExtraEvents {

    private static final ObjectMapper MAPPER = JacksonUtils.enhancedObjectMapper();

    private OutboxExtraEvents() {
    }

    public static List<ExtraEvent> take(JdbcTemplate jdbcTemplate, Long memberId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, payload FROM outbox_message WHERE topic = ? AND message_key = ? ORDER BY id",
                ExtraTopics.EVENTS, String.valueOf(memberId));
        rows.forEach(row -> jdbcTemplate.update("DELETE FROM outbox_message WHERE id = ?", row.get("id")));
        return rows.stream().map(row -> read((String) row.get("payload"))).toList();
    }

    // 꺼낸 이벤트를 증권사 워커에 넘긴다 — 릴레이와 Kafka 를 건너뛴 전달.
    public static void deliver(JdbcTemplate jdbcTemplate, ExtraEventConsumer consumer, Long memberId) {
        take(jdbcTemplate, memberId).forEach(consumer::consume);
    }

    private static ExtraEvent read(String payload) {
        try {
            return MAPPER.readValue(payload, ExtraEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(payload, e);
        }
    }
}
