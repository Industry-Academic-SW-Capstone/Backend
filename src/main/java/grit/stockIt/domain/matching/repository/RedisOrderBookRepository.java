package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.ZoneId;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisOrderBookRepository {

    private static final String ORDER_QUEUE_KEY = "sim:order:book:%s:%s";
    private static final String ORDER_DATA_KEY = "sim:order:data:%s";

    private static final String FIELD_PRICE = "price";
    private static final String FIELD_REMAINING = "remainingQuantity";
    private static final String FIELD_TOTAL = "totalQuantity";
    private static final String FIELD_METHOD = "orderMethod";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_ACCOUNT_ID = "accountId";
    private static final String FIELD_STOCK_CODE = "stockCode";

    private final StringRedisTemplate redisTemplate;

    public void addOrder(Order order) {
        if (!isActiveStatus(order.getStatus())) {
            return;
        }
        String queueKey = queueKey(order.getStock().getCode(), order.getOrderMethod());
        String dataKey = dataKey(order.getOrderId());

        Map<String, String> data = new HashMap<>();
        data.put(FIELD_PRICE, order.getPrice().toPlainString());
        data.put(FIELD_REMAINING, Integer.toString(order.getRemainingQuantity()));
        data.put(FIELD_TOTAL, Integer.toString(order.getQuantity()));
        data.put(FIELD_METHOD, order.getOrderMethod().name());
        data.put(FIELD_TIMESTAMP, Long.toString(createdAt(order)));
        data.put(FIELD_ACCOUNT_ID, order.getAccount().getAccountId().toString());
        data.put(FIELD_STOCK_CODE, order.getStock().getCode());

        redisTemplate.opsForHash().putAll(dataKey, data);
        redisTemplate.opsForZSet().add(queueKey, order.getOrderId().toString(), order.getPrice().doubleValue());
    }

    public void removeOrder(Long orderId, String stockCode, OrderMethod orderMethod) {
        String queueKey = queueKey(stockCode, orderMethod);
        String dataKey = dataKey(orderId);
        redisTemplate.opsForZSet().remove(queueKey, orderId.toString());
        redisTemplate.delete(dataKey);
    }

    public void updateRemainingQuantity(Long orderId, String stockCode, OrderMethod orderMethod, int remainingQuantity) {
        String dataKey = dataKey(orderId);
        if (remainingQuantity <= 0) {
            removeOrder(orderId, stockCode, orderMethod);
            return;
        }
        redisTemplate.opsForHash().put(dataKey, FIELD_REMAINING, Integer.toString(remainingQuantity));
    }

    public List<OrderBookEntry> fetchMatchingEntries(String stockCode, OrderMethod takerMethod, BigDecimal priceLimit, int maxOrders) {
        OrderMethod targetMethod = takerMethod == OrderMethod.BUY ? OrderMethod.SELL : OrderMethod.BUY;
        String queueKey = queueKey(stockCode, targetMethod);
        Set<String> members = targetMethod == OrderMethod.SELL
                ? redisTemplate.opsForZSet().rangeByScore(queueKey, Double.NEGATIVE_INFINITY, priceLimit.doubleValue(), 0, maxOrders)
                : redisTemplate.opsForZSet().reverseRangeByScore(queueKey, priceLimit.doubleValue(), Double.POSITIVE_INFINITY, 0, maxOrders);

        if (members == null || members.isEmpty()) {
            return List.of();
        }

        List<String> orderIds = new ArrayList<>(members);
        List<Object> rawResults = fetchOrderData(orderIds);

        List<OrderBookEntry> entries = new ArrayList<>();
        for (int i = 0; i < orderIds.size(); i++) {
            Object raw = rawResults.get(i);
            if (!(raw instanceof List<?> values)) {
                continue;
            }
            OrderBookEntry entry = mapToEntry(orderIds.get(i), values);
            if (entry != null) {
                entries.add(entry);
            } else {
                log.debug("Redis 주문북 엔트리가 손상되어 제거합니다. orderId={}", orderIds.get(i));
                redisTemplate.opsForZSet().remove(queueKey, orderIds.get(i));
            }
        }

        return entries;
    }

    private String queueKey(String stockCode, OrderMethod method) {
        return ORDER_QUEUE_KEY.formatted(stockCode, method.name());
    }

    private String dataKey(Long orderId) {
        return ORDER_DATA_KEY.formatted(orderId);
    }

    private boolean isActiveStatus(OrderStatus status) {
        return status == OrderStatus.PENDING || status == OrderStatus.PARTIALLY_FILLED;
    }

    private long createdAt(Order order) {
        if (order.getCreatedAt() != null) {
            return order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return Instant.now().toEpochMilli();
    }

    private List<Object> fetchOrderData(List<String> orderIds) {
        try {
            RedisSerializer<String> serializer = redisTemplate.getStringSerializer();
            return redisTemplate.executePipelined((RedisConnection connection) -> {
                for (String orderId : orderIds) {
                    byte[] key = serializer.serialize(dataKey(Long.valueOf(orderId)));
                    connection.hashCommands().hMGet(
                            key,
                            serializer.serialize(FIELD_PRICE),
                            serializer.serialize(FIELD_REMAINING),
                            serializer.serialize(FIELD_TOTAL),
                            serializer.serialize(FIELD_METHOD),
                            serializer.serialize(FIELD_TIMESTAMP),
                            serializer.serialize(FIELD_ACCOUNT_ID),
                            serializer.serialize(FIELD_STOCK_CODE)
                    );
                }
                return null;
            });
        } catch (DataAccessException e) {
            log.error("Redis 파이프라인 조회 실패", e);
            throw e;
        }
    }

    private OrderBookEntry mapToEntry(String orderIdStr, List<?> values) {
        if (values.size() < 7) {
            return null;
        }
        String priceStr = toString(values.get(0));
        String remainingStr = toString(values.get(1));
        String totalStr = toString(values.get(2));
        String methodStr = toString(values.get(3));
        String timestampStr = toString(values.get(4));
        String accountIdStr = toString(values.get(5));
        String stockCode = toString(values.get(6));

        if (priceStr == null || remainingStr == null || methodStr == null || timestampStr == null || stockCode == null) {
            return null;
        }

        try {
            Long orderId = Long.valueOf(orderIdStr);
            BigDecimal price = new BigDecimal(priceStr);
            int remaining = Integer.parseInt(remainingStr);
            int total = totalStr != null ? Integer.parseInt(totalStr) : remaining;
            OrderMethod orderMethod = OrderMethod.valueOf(methodStr);
            long timestamp = Long.parseLong(timestampStr);
            Long accountId = accountIdStr != null ? Long.valueOf(accountIdStr) : null;

            return new OrderBookEntry(orderId, stockCode, orderMethod, price, remaining, total, timestamp, accountId);
        } catch (Exception e) {
            log.warn("Redis 주문북 엔트리 파싱 실패. orderId={}, values={}", orderIdStr, values, e);
            return null;
        }
    }

    private String toString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return redisTemplate.getStringSerializer().deserialize(bytes);
        }
        return value.toString();
    }
}

