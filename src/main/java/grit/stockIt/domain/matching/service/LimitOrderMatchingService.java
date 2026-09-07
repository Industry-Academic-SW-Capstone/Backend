package grit.stockIt.domain.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.lock.MatchingLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 종목별 체결 이벤트를 하나씩 꺼내 매칭 정산으로 넘긴다.
 *
 * <p>락 획득/해제는 {@link MatchingLock}에 위임한다(Redis SETNX 또는 PostgreSQL advisory).
 * 이벤트 큐는 두 백엔드 모두 Redis를 쓴다 — 오더북 자료구조만 바꿔 성능을 비교하기 위한
 * 단일 변수 통제이며, 큐 연산은 양쪽 모두 LPOP 1회로 동일해 비교를 편향시키지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderMatchingService {

    private static final String LIMIT_EVENT_QUEUE_KEY_PATTERN = "sim:limit:event:%s";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LimitOrderExecutionService limitOrderExecutionService;
    private final MatchingLock matchingLock;

    // 락 획득 후 이벤트 1건을 꺼내 정산에 넘긴다. 락 획득 실패 시 이벤트는 큐에 남는다.
    public List<Execution> consumeNextEvent(String stockCode) {
        return matchingLock.tryRun(stockCode, () -> {
            LimitOrderFillEvent event = fetchNextFillEvent(buildEventQueueKey(stockCode));
            if (event == null) {
                return List.<Execution>of();
            }
            // 내부 서비스 호출 (프록시를 통해 @Transactional 동작)
            return limitOrderExecutionService.distributeEvent(stockCode, event);
        }).orElseGet(List::of);
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

    private String buildEventQueueKey(String stockCode) {
        return LIMIT_EVENT_QUEUE_KEY_PATTERN.formatted(stockCode);
    }
}
