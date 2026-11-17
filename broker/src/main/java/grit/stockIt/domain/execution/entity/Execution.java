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
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "execution")
// 증권사의 체결 내역. 거래소의 체결 통보로 정산할 때 함께 적고, 체결 번호는 거래소가 붙인 것을 그대로 쓴다.
public class Execution extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "execution_id")
    private Long executionId;

    // 번호를 직접 붙이므로 저장 때 이미 있는지 먼저 조회(merge)하지 않게 새 행임을 알린다.
    @Transient
    private boolean isNew = true;

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

    private Execution(Long executionId, Order order, BigDecimal price, int quantity) {
        this.executionId = executionId;
        this.order = order;
        this.account = order.getAccount();
        this.stock = order.getStock();
        this.price = price;
        this.quantity = quantity;
        this.orderType = order.getOrderType();
        this.orderMethod = order.getOrderMethod();
    }

    public static Execution of(Long executionId, Order order, BigDecimal price, int quantity) {
        if (executionId == null) {
            throw new IllegalArgumentException("체결 번호가 필요합니다.");
        }
        validate(order, price, quantity);
        return new Execution(executionId, order, price, quantity);
    }

    @Override
    public Long getId() {
        return executionId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
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

