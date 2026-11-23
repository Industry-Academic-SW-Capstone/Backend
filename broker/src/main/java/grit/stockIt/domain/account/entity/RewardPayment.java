package grit.stockIt.domain.account.entity;

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

// 미션 보상으로 계좌 현금이 바뀐 기록. 정산 원장과 이것을 더하면 계좌 현금을 다시 맞출 수 있다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "reward_payment")
public class RewardPayment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reward_payment_id")
    private Long rewardPaymentId;

    // 부가 서비스가 보낸 지급 요청의 이벤트 번호. 같은 요청이 두 번 기록되지 않게 한다.
    @Column(name = "event_id", nullable = false, unique = true, length = 64)
    private String eventId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    private RewardPayment(String eventId, Long accountId, Long memberId, BigDecimal amount) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.memberId = memberId;
        this.amount = amount;
    }

    public static RewardPayment of(String eventId, Long accountId, Long memberId, BigDecimal amount) {
        if (eventId == null || accountId == null || memberId == null || amount == null) {
            throw new IllegalArgumentException("보상 지급 기록에 필요한 값이 빠졌습니다.");
        }
        return new RewardPayment(eventId, accountId, memberId, amount);
    }
}
