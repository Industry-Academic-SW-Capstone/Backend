package grit.stockIt.domain.matching.entity;

import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 거래소 오더북의 주문 한 건. 주문 번호는 증권사가 붙인 것을 그대로 쓴다. 이 행을 바꾸는 것은 그 종목의 워커 하나뿐이다.
// 시간 우선은 거래소에 도착한 시각(acceptedAt)으로 정한다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "exchange_order")
public class ExchangeOrder {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_method", nullable = false, length = 20)
    private OrderMethod orderMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType;

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExchangeOrderStatus status;

    @Column(name = "accepted_at", nullable = false)
    private LocalDateTime acceptedAt;

    public static ExchangeOrder accept(Long orderId, Long accountId, String stockCode, OrderMethod orderMethod,
                                       OrderType orderType, BigDecimal price, int quantity) {
        ExchangeOrder order = new ExchangeOrder();
        order.orderId = orderId;
        order.accountId = accountId;
        order.stockCode = stockCode;
        order.orderMethod = orderMethod;
        order.orderType = orderType;
        order.price = price;
        order.quantity = quantity;
        order.filledQuantity = 0;
        order.status = ExchangeOrderStatus.OPEN;
        order.acceptedAt = LocalDateTime.now();
        return order;
    }

    public void applyFill(int fillQuantity) {
        if (fillQuantity <= 0 || fillQuantity > getRemainingQuantity()) {
            throw new IllegalArgumentException("체결 수량이 남은 수량을 벗어납니다. orderId=" + orderId
                    + " fill=" + fillQuantity + " remaining=" + getRemainingQuantity());
        }
        filledQuantity += fillQuantity;
        if (getRemainingQuantity() == 0) {
            status = ExchangeOrderStatus.FILLED;
        }
    }

    public void cancel() {
        status = ExchangeOrderStatus.CANCELLED;
    }

    public boolean isOpen() {
        return status == ExchangeOrderStatus.OPEN;
    }

    public int getRemainingQuantity() {
        return quantity - filledQuantity;
    }
}
