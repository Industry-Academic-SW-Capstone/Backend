package grit.stockIt.domain.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderMatchingService {

    private static final String LIMIT_EVENT_QUEUE_KEY_PATTERN = "sim:limit:event:%s";
    private static final String LIMIT_LOCK_KEY_PATTERN = "sim:limit:lock:%s";
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LimitOrderExecutionService limitOrderExecutionService;

    @Value("${matching.limit-lock-ttl-seconds:5}")
    private long lockTtlSeconds;

    // 락 획득/해제만 담당 (트랜잭션 없음)
    public List<Execution> consumeNextEvent(String stockCode) {
        String lockKey = buildLockKey(stockCode);
        String lockToken = UUID.randomUUID().toString();

        if (!acquireLock(lockKey, lockToken)) {
            log.debug("지정가 매칭 락 획득 실패. stockCode={}", stockCode);
            return List.of();
        }

        try {
            LimitOrderFillEvent event = fetchNextFillEvent(buildEventQueueKey(stockCode));
            if (event == null) {
                return List.of();
            }
            // 내부 서비스 호출 (프록시를 통해 @Transactional 동작)
            return limitOrderExecutionService.distributeEvent(stockCode, event);
        } finally {
            releaseLock(lockKey, lockToken);
        }
    }

    private LimitOrderFillEvent fetchNextFillEvent(String queueKey) {
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

    private boolean acquireLock(String lockKey, String lockToken) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(
                        lockKey,
                        lockToken,
                        Duration.ofSeconds(lockTtlSeconds)
                )
        );
    }

    private void releaseLock(String lockKey, String lockToken) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
    }

    private String buildEventQueueKey(String stockCode) {
        return LIMIT_EVENT_QUEUE_KEY_PATTERN.formatted(stockCode);
    }

    private String buildLockKey(String stockCode) {
        return LIMIT_LOCK_KEY_PATTERN.formatted(stockCode);
    }
}
