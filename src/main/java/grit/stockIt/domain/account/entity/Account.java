package grit.stockIt.domain.account.entity;

import jakarta.persistence.*;
import lombok.*;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.global.common.BaseEntity;
import java.math.BigDecimal;

@Entity
@Table(name = "account",
       uniqueConstraints = @UniqueConstraint(name = "uk_account_member_contest", columnNames = {"member_id", "contest_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Long accountId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "contest_id", nullable = false)
    private Contest contest;

    @Column(name = "account_name", nullable = false, length = 100)
    private String accountName;

    @Builder.Default
    @Column(name = "cash", nullable = false, precision = 19, scale = 2)
    private BigDecimal cash = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "hold_amount", nullable = false, precision = 19, scale = 2, columnDefinition = "numeric(19,2) default 0")
    private BigDecimal holdAmount = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    public BigDecimal getAvailableCash() {
        return cash.subtract(holdAmount);
    }

    public void increaseHoldAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("홀딩 금액은 0보다 커야 합니다.");
        }
        this.holdAmount = this.holdAmount.add(amount);
    }

    public void decreaseHoldAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("감소 금액은 0보다 커야 합니다.");
        }
        if (amount.compareTo(this.holdAmount) > 0) {
            throw new IllegalStateException("홀딩 금액보다 큰 금액을 감소시킬 수 없습니다.");
        }
        this.holdAmount = this.holdAmount.subtract(amount);
    }

    public void decreaseCash(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("차감 금액은 0보다 커야 합니다.");
        }
        this.cash = this.cash.subtract(amount);
    }
}