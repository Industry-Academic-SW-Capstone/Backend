package grit.stockIt.domain.matching.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis List 기반 이벤트 큐.
 *
 * <p>적재는 {@code RPUSH}, 소비는 {@code LPOP}, 잔여분 되돌리기는 {@code LPUSH}로
 * 모두 1왕복·O(1)이다.
 *
 * <p>소비는 매칭 트랜잭션 바깥에서 일어난다. 즉 {@code LPOP} 이후 정산이 롤백되면
 * 이벤트가 유실된다. RDB 구현과의 차이점이다({@link JpaMatchingEventQueue} 참고).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.queue.backend", havingValue = "redis", matchIfMissing = true)
public class RedisMatchingEventQueue implements MatchingEventQueue {

    private static final String QUEUE_KEY_PATTERN = "sim:limit:event:%s";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void enqueue(String stockCode, LimitOrderFillEvent event) {
        String payload = serialize(stockCode, event);
        if (payload != null) {
            redisTemplate.opsForList().rightPush(queueKey(stockCode), payload);
        }
    }

    @Override
    public LimitOrderFillEvent dequeue(String stockCode) {
        String queueKey = queueKey(stockCode);
        try {
            String rawEvent = redisTemplate.opsForList().leftPop(queueKey);
            if (rawEvent == null) {
                return null;
            }
            return objectMapper.readValue(rawEvent, LimitOrderFillEvent.class);
        } catch (DataAccessException e) {
            log.error("Redis 접근 중 오류 발생. queueKey={}", queueKey, e);
            throw e;
        } catch (Exception e) {
            log.error("지정가 매칭 이벤트 파싱 실패. queueKey={}", queueKey, e);
            return null;
        }
    }

    @Override
    public void requeueFront(String stockCode, LimitOrderFillEvent event) {
        String payload = serialize(stockCode, event);
        if (payload != null) {
            redisTemplate.opsForList().leftPush(queueKey(stockCode), payload);
        }
    }

    @Override
    public long size(String stockCode) {
        Long size = redisTemplate.opsForList().size(queueKey(stockCode));
        return size == null ? 0L : size;
    }

    private String serialize(String stockCode, LimitOrderFillEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("지정가 이벤트 직렬화 실패. stockCode={} event={}", stockCode, event, e);
            return null;
        }
    }

    private String queueKey(String stockCode) {
        return QUEUE_KEY_PATTERN.formatted(stockCode);
    }
}
