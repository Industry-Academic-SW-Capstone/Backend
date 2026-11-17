package grit.stockIt.domain.order.entity;

public enum OrderStatus {
    PENDING, // 미체결
    PARTIALLY_FILLED, // 일부 체결
    FILLED, // 체결
    CANCELLED // 취소
}

