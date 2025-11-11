package grit.stockIt.domain.matching.event;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

public record LimitOrderFillEventMessage(
        String stockCode,
        String eventId,
        OrderMethod orderMethod,
        BigDecimal price,
        int quantity,
        long eventTimestamp
) {
}

