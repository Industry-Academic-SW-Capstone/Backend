package grit.stockIt.domain.matching.dto;

import grit.stockIt.domain.order.entity.OrderMethod;
import java.math.BigDecimal;

public record LimitOrderFillEvent(
        String eventId,
        OrderMethod orderMethod,
        BigDecimal price,
        int quantity,
        long eventTimestamp
) {
}

