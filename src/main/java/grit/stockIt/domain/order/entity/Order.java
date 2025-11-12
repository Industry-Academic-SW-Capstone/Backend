package grit.stockIt.domain.order.entity;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "trade_order")
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_code", nullable = false)
    private Stock stock;

    @Column(name = "price", precision = 19, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20, nullable = false)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_method", length = 20, nullable = false)
    private OrderMethod orderMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    private Order(Account account, Stock stock, BigDecimal price, int quantity, OrderType orderType, OrderMethod orderMethod) {
        validate(account, stock, price, quantity, orderType, orderMethod);
        this.account = account;
        this.stock = stock;
        this.price = price;
        this.quantity = quantity;
        this.orderType = orderType;
        this.orderMethod = orderMethod;
    }

    // 정적 팩토리 메서드 (지정가)
    public static Order createLimitOrder(Account account, Stock stock, BigDecimal price, int quantity, OrderMethod orderMethod) {

        return new Order(account, stock, ensurePositive(price, "가격"), ensurePositive(quantity, "주문 수량"), OrderType.LIMIT, orderMethod);

    }

    // 정적 팩토리 메서드 (시장가)
    public static Order createMarketOrder(Account account, Stock stock, int quantity, OrderMethod orderMethod) {

        return new Order(account, stock, BigDecimal.ZERO, ensurePositive(quantity, "주문 수량"), OrderType.MARKET, orderMethod);

    }

    public void markPending() {
        this.status = OrderStatus.PENDING;
    }

    public void markCancelled() {
        this.status = OrderStatus.CANCELLED;
    }

    public boolean canMatchAgainstPrice(BigDecimal matchPrice, OrderMethod takerMethod) {
        if (matchPrice == null) {
            return false;
        }
        if (takerMethod == OrderMethod.BUY) {
            return OrderMethod.SELL.equals(this.orderMethod) && matchPrice.compareTo(this.price) >= 0;
        }
        return OrderMethod.BUY.equals(this.orderMethod) && matchPrice.compareTo(this.price) <= 0;
    }

    public void applyFill(int fillQuantity) {
        if (fillQuantity <= 0) {
            throw new IllegalArgumentException("체결 수량은 0보다 커야 합니다.");
        }
        if (fillQuantity > getRemainingQuantity()) {
            throw new IllegalArgumentException("체결 수량이 남은 수량을 초과했습니다.");
        }

        this.filledQuantity += fillQuantity;
        if (this.filledQuantity == this.quantity) {
            this.status = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIALLY_FILLED;
        }
    }

    public int getRemainingQuantity() {
        return this.quantity - this.filledQuantity;
    }

    private static void validate(Account account, Stock stock, BigDecimal price, int quantity, OrderType orderType, OrderMethod orderMethod) {

        if (account == null) {
            throw new IllegalArgumentException("계좌 정보가 필요합니다.");
        }
        if (stock == null) {
            throw new IllegalArgumentException("종목 정보가 필요합니다.");
        }
        if (orderType == null) {
            throw new IllegalArgumentException("주문 유형이 지정되지 않았습니다.");
        }
        if (orderMethod == null) {
            throw new IllegalArgumentException("매수/매도 구분이 지정되지 않았습니다.");
        }
        if (orderType == OrderType.LIMIT && (price == null || price.signum() <= 0)) {
            throw new IllegalArgumentException("지정가 주문 가격은 0보다 커야 합니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("주문 수량은 0보다 커야 합니다.");
        }
    }

    private static BigDecimal ensurePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + "은 0보다 커야 합니다.");
        }
        return value;
    }

    private static int ensurePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + "은 0보다 커야 합니다.");
        }
        return value;
    }
}

