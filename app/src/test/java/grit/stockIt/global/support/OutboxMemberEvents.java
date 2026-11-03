package grit.stockIt.global.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.contract.MemberEvent;
import grit.stockIt.contract.MemberTopics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.JacksonUtils;

import java.util.List;
import java.util.Map;

// 릴레이가 없는 테스트에서 증권사가 아웃박스에 적은 회원 이벤트를 꺼낸다. 릴레이처럼 id 순서로 읽고 꺼낸 행은 지운다.
// 테이블은 다른 테스트와 함께 쓰므로 그 회원(메시지 키)의 이벤트만 꺼낸다.
public final class OutboxMemberEvents {

    private static final ObjectMapper MAPPER = JacksonUtils.enhancedObjectMapper();

    private OutboxMemberEvents() {
    }

    public static List<MemberEvent> take(JdbcTemplate jdbcTemplate, Long memberId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, payload FROM outbox_message WHERE topic = ? AND message_key = ? ORDER BY id",
                MemberTopics.EVENTS, String.valueOf(memberId));
        rows.forEach(row -> jdbcTemplate.update("DELETE FROM outbox_message WHERE id = ?", row.get("id")));
        return rows.stream().map(row -> read((String) row.get("payload"))).toList();
    }

    private static MemberEvent read(String payload) {
        try {
            return MAPPER.readValue(payload, MemberEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(payload, e);
        }
    }
}
