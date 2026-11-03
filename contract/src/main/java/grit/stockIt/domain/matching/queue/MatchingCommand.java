package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;

import java.math.BigDecimal;

// matching.commands 메시지. 종류마다 쓰는 필드가 다르다 — FILL 은 틱(eventId~eventTimestamp), CANCEL·EXPIRE 는 orderId,
// ORDER 는 주문 내용(orderId, accountId, orderMethod, orderType, price, quantity). 필드는 추가만 한다.
// enqueuedAt 은 큐 대기와 도착→처리 완료 시간을 재는 기준이다.
public record MatchingCommand(
        MatchingCommandType type,
        String stockCode,
        String eventId,
        OrderMethod orderMethod,
        BigDecimal price,
        int quantity,
        long eventTimestamp,
        Long orderId,
        long enqueuedAt,
        Long accountId,
        OrderType orderType
) {

    public static MatchingCommand fill(String stockCode, LimitOrderFillEvent event, long enqueuedAt) {
        return new MatchingCommand(
                MatchingCommandType.FILL,
                stockCode,
                event.eventId(),
                event.orderMethod(),
                event.price(),
                event.quantity(),
                event.eventTimestamp(),
                null,
                enqueuedAt,
                null,
                null
        );
    }

    public static MatchingCommand order(String stockCode, Long orderId, Long accountId, OrderMethod orderMethod,
                                        OrderType orderType, BigDecimal price, int quantity, long enqueuedAt) {
        return new MatchingCommand(MatchingCommandType.ORDER, stockCode, null, orderMethod, price, quantity, 0L,
                orderId, enqueuedAt, accountId, orderType);
    }

    public static MatchingCommand cancel(String stockCode, Long orderId, long enqueuedAt) {
        return ofOrder(MatchingCommandType.CANCEL, stockCode, orderId, enqueuedAt);
    }

    public static MatchingCommand expire(String stockCode, Long orderId, long enqueuedAt) {
        return ofOrder(MatchingCommandType.EXPIRE, stockCode, orderId, enqueuedAt);
    }

    private static MatchingCommand ofOrder(MatchingCommandType type, String stockCode, Long orderId, long enqueuedAt) {
        return new MatchingCommand(type, stockCode, null, null, null, 0, 0L, orderId, enqueuedAt, null, null);
    }

    // 여기서 걸러내지 않으면 처리 중 예외가 나 같은 명령을 끝없이 재시도하며 파티션이 멈춘다.
    public void validate() {
        if (type == null || isBlank(stockCode) || !hasRequiredFields()) {
            throw new InvalidMatchingCommandException("처리할 수 없는 명령입니다. " + this);
        }
    }

    public LimitOrderFillEvent toFillEvent() {
        return new LimitOrderFillEvent(eventId, orderMethod, price, quantity, eventTimestamp);
    }

    private boolean hasRequiredFields() {
        return switch (type) {
            case FILL -> !isBlank(eventId) && orderMethod != null && price != null && price.signum() > 0 && quantity > 0;
            // 결과를 알릴 수 있을 만큼만 본다. 가격·수량이 틀린 주문은 DLT 가 아니라 거부(REJECTED)로 알려야 증권사가 홀딩을 푼다.
            case ORDER -> orderId != null && accountId != null && orderMethod != null;
            case CANCEL, EXPIRE -> orderId != null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
