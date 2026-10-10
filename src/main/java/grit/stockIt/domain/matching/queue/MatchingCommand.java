package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

// matching.commands 메시지. 종류마다 쓰는 필드가 다르다 - FILL 은 틱(eventId~eventTimestamp), CANCEL 은 orderId.
// enqueuedAt 은 큐 대기와 도착->처리 완료 시간을 재는 기준이다.
public record MatchingCommand(
        MatchingCommandType type,
        String stockCode,
        String eventId,
        OrderMethod orderMethod,
        BigDecimal price,
        int quantity,
        long eventTimestamp,
        Long orderId,
        long enqueuedAt
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
                enqueuedAt
        );
    }

    public static MatchingCommand cancel(String stockCode, Long orderId, long enqueuedAt) {
        return new MatchingCommand(MatchingCommandType.CANCEL, stockCode, null, null, null, 0, 0L, orderId, enqueuedAt);
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
            case CANCEL -> orderId != null;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
