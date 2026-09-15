package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class OrderBookRepository {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // 오더북에 올라와 있는(=미체결 잔량이 있는) 주문 상태.
    private static final String ACTIVE_STATUS_PREDICATE = "o.status IN ('PENDING', 'PARTIALLY_FILLED')";

    private static final String BASE_COLUMNS = """
            SELECT o.order_id, o.stock_code, o.order_method, o.price,
                   (o.quantity - o.filled_quantity) AS remaining_quantity,
                   o.quantity, o.created_at, o.account_id
            FROM trade_order o
            """;

    private final EntityManager entityManager;

    // 체결 이벤트에 대응할 반대 방향 주문 후보를 가격·시간 우선으로 최대 maxOrders건 조회한다.
    // takerMethod 가 BUY면 SELL 후보를 체결가 이하에서, SELL이면 BUY 후보를 체결가 이상에서 찾는다.
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<OrderBookEntry> fetchMatchingEntries(
            String stockCode, OrderMethod takerMethod, BigDecimal priceLimit, int maxOrders) {
        OrderMethod targetMethod = takerMethod == OrderMethod.BUY ? OrderMethod.SELL : OrderMethod.BUY;

        // 매도 후보는 체결가 이하에서 낮은 가격이 우선, 매수 후보는 체결가 이상에서 높은 가격이 우선.
        // 같은 가격이면 먼저 접수된 주문이 우선(시간 우선).
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

    // 주문이 오더북에 남아 있는지(=미체결 잔량이 있는지) 확인한다.
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

    private OrderBookEntry mapToEntry(Object[] row) {
        try {
            return new OrderBookEntry(
                    toLong(row[0]),
                    (String) row[1],
                    OrderMethod.valueOf((String) row[2]),
                    (BigDecimal) row[3],
                    toInt(row[4]),
                    toInt(row[5]),
                    toEpochMillis(row[6]),
                    toLong(row[7])
            );
        } catch (Exception e) {
            log.warn("오더북 행 매핑 실패. row={}", Arrays.toString(row), e);
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
