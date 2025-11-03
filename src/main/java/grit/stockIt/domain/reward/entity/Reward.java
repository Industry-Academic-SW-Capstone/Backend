package grit.stockIt.domain.reward.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import jakarta.persistence.Id;


@Entity
@Getter
@NoArgsConstructor
public class Reward {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rewardId;

    private boolean hasTitle;    // 칭호 보상 여부
    private String titleName;    // 칭호 이름 (칭호가 있는 경우)

    private boolean hasMoney;    // 돈 보상 여부
    private Long amount;         // 보상 금액 (돈이 있는 경우)
}
