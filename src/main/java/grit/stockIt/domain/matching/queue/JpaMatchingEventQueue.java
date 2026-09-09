package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * PostgreSQL 테이블 기반 이벤트 큐 (Redis List 대체).
 *
 * <p>Redis 대비 비용이 더 든다. 소비가 1왕복(LPOP)이 아니라
 * {@code SELECT … FOR UPDATE SKIP LOCKED} + {@code DELETE} 2왕복이고,
 * INSERT/DELETE가 반복되므로 dead tuple이 쌓여 autovacuum 압력이 생긴다.
 * <b>이 비용을 측정하는 것 자체가 벤치마크의 목적 중 하나다.</b>
 *
 * <p>대신 이점이 하나 있다. 소비가 매칭 트랜잭션에 참여하므로
 * <b>정산이 롤백되면 이벤트도 큐로 되돌아간다.</b> Redis 구현은 LPOP이 트랜잭션 밖이라
 * 롤백 시 이벤트가 유실된다.
 *
 * <p>{@code SKIP LOCKED}는 여기서 안전하다. 큐 소비는 종목별 락 안에서만 일어나므로
 * 같은 종목을 두 트랜잭션이 동시에 꺼내지 않는다. (오더북 후보 선택에 SKIP LOCKED를 쓰면
 * 가격·시간 우선 원칙이 깨지지만, 여기서는 항상 맨 앞 1건만 꺼내므로 해당되지 않는다.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.queue.backend", havingValue = "jpa")
public class JpaMatchingEventQueue implements MatchingEventQueue {

    private final EntityManager entityManager;

    @Override
    @Transactional
    public void enqueue(String stockCode, LimitOrderFillEvent event) {
        // 뒤에 추가: 해당 종목 최대 seq_no + 1
        insert(stockCode, event, "COALESCE(MAX(q.seq_no), 0) + 1");
    }

    @Override
    @Transactional
    public void requeueFront(String stockCode, LimitOrderFillEvent event) {
        // 앞에 추가: 해당 종목 최소 seq_no - 1
        insert(stockCode, event, "COALESCE(MIN(q.seq_no), 0) - 1");
    }

    @Override
    @Transactional
    public LimitOrderFillEvent dequeue(String stockCode) {
        String selectSql = """
                SELECT e.id, e.event_id, e.order_method, e.price, e.quantity, e.event_timestamp
                FROM matching_event_queue e
                WHERE e.stock_code = :stockCode
                ORDER BY e.seq_no ASC, e.id ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """;

        List<?> rows = entityManager.createNativeQuery(selectSql)
                .setParameter("stockCode", stockCode)
                .getResultList();

        if (rows.isEmpty()) {
            return null;
        }

        Object[] row = (Object[]) rows.get(0);
        Long id = toLong(row[0]);

        entityManager.createNativeQuery("DELETE FROM matching_event_queue WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();

        try {
            return new LimitOrderFillEvent(
                    (String) row[1],
                    OrderMethod.valueOf((String) row[2]),
                    (BigDecimal) row[3],
                    toInt(row[4]),
                    toLong(row[5])
            );
        } catch (Exception e) {
            log.error("체결 이벤트 매핑 실패. stockCode={} id={}", stockCode, id, e);
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long size(String stockCode) {
        Object count = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM matching_event_queue WHERE stock_code = :stockCode")
                .setParameter("stockCode", stockCode)
                .getSingleResult();
        return count instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * seq_no를 서브쿼리로 계산해 한 문장으로 INSERT한다.
     * 동시 유입으로 같은 seq_no가 나올 수 있으나, 조회 시 id가 타이브레이커라 순서는 결정적이다.
     */
    private void insert(String stockCode, LimitOrderFillEvent event, String seqNoExpression) {
        String sql = """
                INSERT INTO matching_event_queue
                    (stock_code, seq_no, event_id, order_method, price, quantity, event_timestamp, created_at)
                VALUES (
                    :stockCode,
                    (SELECT %s FROM matching_event_queue q WHERE q.stock_code = :stockCode),
                    :eventId, :orderMethod, :price, :quantity, :eventTimestamp, now()
                )
                """.formatted(seqNoExpression);

        entityManager.createNativeQuery(sql)
                .setParameter("stockCode", stockCode)
                .setParameter("eventId", event.eventId())
                .setParameter("orderMethod", event.orderMethod().name())
                .setParameter("price", event.price())
                .setParameter("quantity", event.quantity())
                .setParameter("eventTimestamp", event.eventTimestamp())
                .executeUpdate();
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
