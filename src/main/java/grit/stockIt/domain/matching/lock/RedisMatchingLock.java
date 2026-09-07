package grit.stockIt.domain.matching.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis SETNX 기반 분산 락.
 *
 * <p>액션은 락 밖에서 자기 트랜잭션을 연다. 즉 락 해제와 DB 커밋이 별개 시점이라,
 * 커밋 직전에 TTL이 만료되면 다른 노드가 같은 종목을 동시에 처리할 수 있다.
 * TTL을 정산 시간보다 충분히 길게 잡는 것으로 대응한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.orderbook.backend", havingValue = "redis", matchIfMissing = true)
public class RedisMatchingLock implements MatchingLock {

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

    @Value("${matching.limit-lock-ttl-seconds:5}")
    private long lockTtlSeconds;

    @Override
    public <T> Optional<T> tryRun(String stockCode, Supplier<T> action) {
        String lockKey = LIMIT_LOCK_KEY_PATTERN.formatted(stockCode);
        String lockToken = UUID.randomUUID().toString();

        if (!acquireLock(lockKey, lockToken)) {
            log.debug("지정가 매칭 락 획득 실패. stockCode={}", stockCode);
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(action.get());
        } finally {
            releaseLock(lockKey, lockToken);
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
}
