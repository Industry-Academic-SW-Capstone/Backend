package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

// matching.commands 메시지. enqueuedAt 은 큐 대기와 도착→체결 완료 시간을 재는 기준이다.
public record MatchingCommand(
        MatchingCommandType type,
        String eventId,
        String stockCode,
        OrderMethod orderMethod,
        BigDecimal price,
        int quantity,
        long eventTimestamp,
        long enqueuedAt
) {

    public static MatchingCommand fill(String stockCode, LimitOrderFillEvent event, long enqueuedAt) {
        return new MatchingCommand(
                MatchingCommandType.FILL,
                event.eventId(),
                stockCode,
                event.orderMethod(),
                event.price(),
                event.quantity(),
                event.eventTimestamp(),
                enqueuedAt
        );
    }

    public LimitOrderFillEvent toFillEvent() {
        return new LimitOrderFillEvent(eventId, orderMethod, price, quantity, eventTimestamp);
    }
}
