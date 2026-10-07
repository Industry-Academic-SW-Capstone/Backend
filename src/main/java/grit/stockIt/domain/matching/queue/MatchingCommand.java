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

    // 여기서 걸러내지 않으면 체결 중 예외가 나 같은 명령을 끝없이 재시도하며 파티션이 멈춘다.
    public void validate() {
        if (type == null || isBlank(eventId) || isBlank(stockCode) || orderMethod == null
                || price == null || price.signum() <= 0 || quantity <= 0) {
            throw new InvalidMatchingCommandException("처리할 수 없는 체결 명령입니다. " + this);
        }
    }

    public LimitOrderFillEvent toFillEvent() {
        return new LimitOrderFillEvent(eventId, orderMethod, price, quantity, eventTimestamp);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
