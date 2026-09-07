package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RDB 오더북 구현 — {@code trade_order} 테이블 자체가 오더북이다.
 *
 * <p>Redis 구현과 달리 별도의 오더북 자료구조를 유지하지 않는다. 주문 행의
 * {@code status}와 {@code filled_quantity}가 곧 오더북 상태이므로, 등록·제거·잔여수량
 * 갱신은 이미 정산 트랜잭션이 수행한 DB 변경으로 끝난다. 그 결과 이중 쓰기가 존재하지 않고,
 * 유령 주문·주문 누락도 원리적으로 발생하지 않는다.
 *
 * <p>조회 성능은 부분 인덱스 {@code idx_orderbook_active}에 의존한다
 * ({@code V10__add_orderbook_index.sql} 참고). 인덱스 선두가 등가 조건
 * (stock_code, order_method)이고 뒤가 (price, created_at)이라, 정렬을 위한 추가 sort 없이
 * 인덱스 순서대로 스캔한다. B-tree는 역방향 스캔이 가능하므로 매수·매도 양쪽 정렬을
 * 인덱스 하나로 처리한다.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.orderbook.backend", havingValue = "jpa")
public class JpaOrderBookStore implements OrderBookStore {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 오더북에 올라와 있는(=미체결 잔량이 있는) 주문 상태. 부분 인덱스 조건과 반드시 일치해야 한다. */
    private static final String ACTIVE_STATUS_PREDICATE = "o.status IN ('PENDING', 'PARTIALLY_FILLED')";

    private static final String BASE_COLUMNS = """
            SELECT o.order_id, o.stock_code, o.order_method, o.price,
                   (o.quantity - o.filled_quantity) AS remaining_quantity,
                   o.quantity, o.created_at, o.account_id
            FROM trade_order o
            """;

    private final EntityManager entityManager;

    /**
     * no-op. 주문은 이미 {@code trade_order}에 저장돼 있고, 그 행이 곧 오더북 엔트리다.
     * Redis 구현이 필요로 하던 커밋 후 이중 쓰기가 여기서는 존재하지 않는다.
     */
    @Override
    public void addOrder(Order order) {
        // 의도적 no-op — 클래스 주석 참고
    }

    /**
     * no-op. 주문 상태가 FILLED/CANCELLED로 바뀌는 순간 조회 조건에서 자동으로 빠진다.
     */
    @Override
    public void removeOrder(Long orderId, String stockCode, OrderMethod orderMethod) {
        // 의도적 no-op — 클래스 주석 참고
    }

    /**
     * no-op. 잔여 수량은 {@code quantity - filled_quantity}로 조회 시점에 계산되며,
     * {@code filled_quantity}는 정산 트랜잭션이 이미 갱신했다.
     */
    @Override
    public void updateRemainingQuantity(Long orderId, String stockCode, OrderMethod orderMethod, int remainingQuantity) {
        // 의도적 no-op — 클래스 주석 참고
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<OrderBookEntry> fetchMatchingEntries(String stockCode, OrderMethod takerMethod, BigDecimal priceLimit, int maxOrders) {
        OrderMethod targetMethod = takerMethod == OrderMethod.BUY ? OrderMethod.SELL : OrderMethod.BUY;

        // 매도 후보는 체결가 이하에서 낮은 가격이 우선, 매수 후보는 체결가 이상에서 높은 가격이 우선.
        // 동일 가격이면 먼저 접수된 주문이 우선(시간 우선).
        String priceCondition = targetMethod == OrderMethod.SELL ? "o.price <= :priceLimit" : "o.price >= :priceLimit";
        String priceOrder = targetMethod == OrderMethod.SELL ? "ASC" : "DESC";

        String sql = BASE_COLUMNS + """
                WHERE o.stock_code = :stockCode
                  AND o.order_method = :orderMethod
                  AND %s
                  AND o.quantity > o.filled_quantity
                  AND %s
                ORDER BY o.price %s, o.created_at ASC, o.order_id ASC
                LIMIT :maxOrders
                """.formatted(ACTIVE_STATUS_PREDICATE, priceCondition, priceOrder);

        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("stockCode", stockCode)
                .setParameter("orderMethod", targetMethod.name())
                .setParameter("priceLimit", priceLimit)
                .setParameter("maxOrders", maxOrders)
                .getResultList();

        List<OrderBookEntry> entries = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            OrderBookEntry entry = mapToEntry(row);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(Long orderId, String stockCode, OrderMethod orderMethod) {
        String sql = """
                SELECT COUNT(*)
                FROM trade_order o
                WHERE o.order_id = :orderId
                  AND o.stock_code = :stockCode
                  AND o.order_method = :orderMethod
                  AND %s
                  AND o.quantity > o.filled_quantity
                """.formatted(ACTIVE_STATUS_PREDICATE);

        Object count = entityManager.createNativeQuery(sql)
                .setParameter("orderId", orderId)
                .setParameter("stockCode", stockCode)
                .setParameter("orderMethod", orderMethod.name())
                .getSingleResult();

        return count instanceof Number number && number.longValue() > 0;
    }

    /**
     * 항상 빈 맵을 반환한다. 오더북과 주문 원본이 같은 행이므로 유령 주문이 생길 수 없고,
     * 따라서 정리할 대상도 없다. RDB 백엔드에서는 Redis-DB 동기화 배치가 사실상 무동작이 된다.
     */
    @Override
    public Map<String, Set<Long>> getAllOrderIdsByStock() {
        return Map.of();
    }

    private OrderBookEntry mapToEntry(Object[] row) {
        try {
            Long orderId = toLong(row[0]);
            String stockCode = (String) row[1];
            OrderMethod orderMethod = OrderMethod.valueOf((String) row[2]);
            BigDecimal price = (BigDecimal) row[3];
            int remaining = toInt(row[4]);
            int total = toInt(row[5]);
            long createdAtMillis = toEpochMillis(row[6]);
            Long accountId = toLong(row[7]);

            return new OrderBookEntry(orderId, stockCode, orderMethod, price, remaining, total, createdAtMillis, accountId);
        } catch (Exception e) {
            log.warn("오더북 행 매핑 실패. row={}", java.util.Arrays.toString(row), e);
            return null;
        }
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int toInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private long toEpochMillis(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().atZone(KST).toInstant().toEpochMilli();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atZone(KST).toInstant().toEpochMilli();
        }
        return Instant.now().toEpochMilli();
    }
}
