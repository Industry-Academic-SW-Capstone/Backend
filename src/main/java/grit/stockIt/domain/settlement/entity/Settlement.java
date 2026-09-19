package grit.stockIt.domain.settlement.entity;

import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "settlement")
public class Settlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "execution_id", nullable = false, unique = true)
    private Long executionId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    // 계좌 현금의 증감. 매수는 음수, 매도는 양수다.
    @Column(name = "cash_delta", precision = 19, scale = 2, nullable = false)
    private BigDecimal cashDelta;

    // 보유 수량의 증감. 매수는 양수, 매도는 음수다.
    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    private Settlement(Long executionId, Long accountId, BigDecimal cashDelta, int quantityDelta) {
        this.executionId = executionId;
        this.accountId = accountId;
        this.cashDelta = cashDelta;
        this.quantityDelta = quantityDelta;
    }

    public static Settlement of(Long executionId, Long accountId, BigDecimal cashDelta, int quantityDelta) {
        if (executionId == null) {
            throw new IllegalArgumentException("체결 식별자가 필요합니다.");
        }
        if (accountId == null) {
            throw new IllegalArgumentException("계좌 식별자가 필요합니다.");
        }
        if (cashDelta == null) {
            throw new IllegalArgumentException("현금 증감이 필요합니다.");
        }
        return new Settlement(executionId, accountId, cashDelta, quantityDelta);
    }
}
