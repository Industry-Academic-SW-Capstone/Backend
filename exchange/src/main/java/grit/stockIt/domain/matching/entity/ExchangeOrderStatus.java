package grit.stockIt.domain.matching.entity;

public enum ExchangeOrderStatus {
    // 오더북에 남아 있다(일부 체결 포함).
    OPEN,
    FILLED,
    CANCELLED
}
