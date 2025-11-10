package grit.stockIt.domain.execution.entity;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
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
@Table(name = "execution")
public class Execution extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "execution_id")
    private Long executionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", length = 20, nullable = false)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_method", length = 20, nullable = false)
    private OrderMethod orderMethod;

    private Execution(Order order, BigDecimal price, int quantity) {
        this.order = order;
        this.account = order.getAccount();
        this.stock = order.getStock();
        this.price = price;
        this.quantity = quantity;
        this.orderType = order.getOrderType();
        this.orderMethod = order.getOrderMethod();
    }

    public static Execution of(Order order, BigDecimal price, int quantity) {
        validate(order, price, quantity);
        return new Execution(order, price, quantity);
    }

    private static void validate(Order order, BigDecimal price, int quantity) {
        if (order == null) {
            throw new IllegalArgumentException("주문 정보가 필요합니다.");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("체결 가격은 0보다 커야 합니다.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("체결 수량은 0보다 커야 합니다.");
        }
    }
}

