package grit.stockIt.domain.order.dto;

import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        Long orderId,
        Long accountId,
        String stockCode,
        String stockName,
        OrderType orderType,
        OrderMethod orderMethod,
        OrderStatus status,
        BigDecimal price,
        int quantity,
        int filledQuantity,
        int remainingQuantity,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getAccount().getAccountId(),
                order.getStock().getCode(),
                order.getStock().getName(),
                order.getOrderType(),
                order.getOrderMethod(),
                order.getStatus(),
                order.getOrderType() == OrderType.MARKET ? null : order.getPrice(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.getRemainingQuantity(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}

