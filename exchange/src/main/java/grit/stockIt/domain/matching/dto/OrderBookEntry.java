package grit.stockIt.domain.matching.dto;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

public record OrderBookEntry(
        Long orderId,
        String stockCode,
        OrderMethod orderMethod,
        BigDecimal price,
        int remainingQuantity,
        int totalQuantity,
        long createdAtEpochMillis,
        Long accountId
) {
    public boolean isExhausted() {
        return remainingQuantity <= 0;
    }

    public boolean willBeFilledCompletely(int fillQuantity) {
        return remainingQuantity - fillQuantity <= 0;
    }
}

