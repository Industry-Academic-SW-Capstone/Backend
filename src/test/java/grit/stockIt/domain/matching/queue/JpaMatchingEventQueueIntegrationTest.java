package grit.stockIt.domain.matching.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RDB 이벤트 큐({@link JpaMatchingEventQueue}) 검증.
 *
 * <p>Redis List의 의미(RPUSH / LPOP / LPUSH)를 테이블로 옮긴 것이므로,
 * 계약 검증과 함께 Redis 구현과의 동치성도 확인한다. 두 구현이 다른 순서를 돌려주면
 * 가격·시간 우선 배분이 달라져 벤치마크 비교가 성립하지 않는다.
 *
 * <p>인덱스는 엔티티가 아니라 마이그레이션에만 있으므로, 오더북 테스트와 동일하게
 * {@code @Sql}로 직접 적용한다 — V11 DDL 자체의 유효성도 함께 검증된다.
 */
@DisplayName("RDB 이벤트 큐 (통합 테스트)")
@TestPropertySource(properties = "matching.queue.backend=jpa")
@Sql(scripts = "classpath:db/migration/V11__add_matching_event_queue.sql")
class JpaMatchingEventQueueIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MatchingEventQueue queue;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String stockCode;

    @BeforeEach
    void setUp() {
        // 종목별 큐라 종목코드를 새로 만들면 다른 테스트와 격리된다.
        stockCode = "Q" + UUID.randomUUID().toString().substring(0, 8);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("선택된 백엔드는 RDB 큐 구현이다")
    void backendIsJpaImplementation() {
        assertThat(queue).isInstanceOf(JpaMatchingEventQueue.class);
    }

    @Test
    @DisplayName("넣은 순서대로 꺼낸다 (FIFO)")
    void dequeuesInEnqueueOrder() {
        queue.enqueue(stockCode, event("first", 10));
        queue.enqueue(stockCode, event("second", 20));
        queue.enqueue(stockCode, event("third", 30));

        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("first");
        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("second");
        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("third");
    }

    @Test
    @DisplayName("잔여분 되돌리기는 일반 유입보다 먼저 소비된다")
    void requeuedEventIsConsumedBeforePendingOnes() {
        queue.enqueue(stockCode, event("pending-1", 10));
        queue.enqueue(stockCode, event("pending-2", 20));

        queue.requeueFront(stockCode, event("residual", 5));

        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("residual");
        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("pending-1");
        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("pending-2");
    }

    @Test
    @DisplayName("연속으로 되돌린 잔여분은 나중 것이 먼저 나온다 (LPUSH 의미)")
    void consecutiveRequeuesFollowLastInFirstOut() {
        queue.enqueue(stockCode, event("pending", 10));
        queue.requeueFront(stockCode, event("residual-1", 5));
        queue.requeueFront(stockCode, event("residual-2", 3));

        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("residual-2");
        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("residual-1");
        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("pending");
    }

    @Test
    @DisplayName("빈 큐는 null을 돌려준다")
    void emptyQueueReturnsNull() {
        assertThat(queue.dequeue(stockCode)).isNull();
    }

    @Test
    @DisplayName("종목별로 큐가 분리된다")
    void queuesAreIsolatedPerStock() {
        String otherStock = "Q" + UUID.randomUUID().toString().substring(0, 8);
        queue.enqueue(stockCode, event("mine", 10));
        queue.enqueue(otherStock, event("theirs", 20));

        assertThat(queue.dequeue(stockCode).eventId()).isEqualTo("mine");
        assertThat(queue.dequeue(stockCode)).isNull();
        assertThat(queue.dequeue(otherStock).eventId()).isEqualTo("theirs");
    }

    @Test
    @DisplayName("size가 적체량을 돌려준다 — 부하 측정에서 큐 밀림을 관측하는 지표")
    void sizeReportsBacklog() {
        assertThat(queue.size(stockCode)).isZero();

        queue.enqueue(stockCode, event("a", 10));
        queue.enqueue(stockCode, event("b", 20));
        assertThat(queue.size(stockCode)).isEqualTo(2);

        queue.dequeue(stockCode);
        assertThat(queue.size(stockCode)).isEqualTo(1);
    }

    @Test
    @DisplayName("이벤트 필드가 손실 없이 복원된다")
    void preservesEventFields() {
        LimitOrderFillEvent original = new LimitOrderFillEvent(
                "evt-1", OrderMethod.SELL, new BigDecimal("70123.45"), 37, 1_725_000_000_000L);

        queue.enqueue(stockCode, original);
        LimitOrderFillEvent restored = queue.dequeue(stockCode);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.orderMethod()).isEqualTo(original.orderMethod());
        assertThat(restored.price()).isEqualByComparingTo(original.price());
        assertThat(restored.quantity()).isEqualTo(original.quantity());
        assertThat(restored.eventTimestamp()).isEqualTo(original.eventTimestamp());
    }

    @Test
    @DisplayName("Redis 구현과 같은 순서를 돌려준다 — 벤치마크 동치성 전제")
    void producesSameOrderAsRedisImplementation() {
        MatchingEventQueue redisQueue = new RedisMatchingEventQueue(redisTemplate, objectMapper);
        String redisStock = "R" + UUID.randomUUID().toString().substring(0, 8);

        // 두 구현에 같은 순서로 같은 조작을 가한다.
        for (MatchingEventQueue target : List.of(queue, redisQueue)) {
            String code = target == queue ? stockCode : redisStock;
            target.enqueue(code, event("a", 10));
            target.enqueue(code, event("b", 20));
            target.requeueFront(code, event("residual", 5));
            target.enqueue(code, event("c", 30));
        }

        assertThat(drain(queue, stockCode))
                .containsExactlyElementsOf(drain(redisQueue, redisStock));
    }

    private List<String> drain(MatchingEventQueue target, String code) {
        return java.util.stream.Stream.generate(() -> target.dequeue(code))
                .takeWhile(java.util.Objects::nonNull)
                .map(LimitOrderFillEvent::eventId)
                .toList();
    }

    private LimitOrderFillEvent event(String eventId, int quantity) {
        return new LimitOrderFillEvent(
                eventId, OrderMethod.SELL, new BigDecimal("70000"), quantity, System.currentTimeMillis());
    }
}
