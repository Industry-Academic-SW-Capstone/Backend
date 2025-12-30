package grit.stockIt.domain.order.entity;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "order_hold")
public class OrderHold extends BaseEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "hold_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal holdAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "hold_status", length = 20, nullable = false)
    private OrderHoldStatus status = OrderHoldStatus.ACTIVE;

    private OrderHold(Order order, Account account, BigDecimal holdAmount) {
        this.order = order;
        this.account = account;
        this.holdAmount = holdAmount;
    }

    public static OrderHold create(Order order, Account account, BigDecimal holdAmount) {
        if (holdAmount == null || holdAmount.signum() < 0) {
            throw new IllegalArgumentException("홀딩 금액은 0 이상이어야 합니다.");
        }
        return new OrderHold(order, account, holdAmount);
    }

    public void decreaseHoldAmount(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("감소 금액은 0 이상이어야 합니다.");
        }
        if (amount.compareTo(this.holdAmount) > 0) {
            throw new IllegalStateException("홀딩 금액보다 큰 금액을 감소시킬 수 없습니다.");
        }
        this.holdAmount = this.holdAmount.subtract(amount);
        if (this.holdAmount.signum() == 0) {
            this.status = OrderHoldStatus.RELEASED;
        }
    }

    public void release() {
        this.holdAmount = BigDecimal.ZERO;
        this.status = OrderHoldStatus.RELEASED;
    }
}

