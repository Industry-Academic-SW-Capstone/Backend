package grit.stockIt.domain.mission.entity;

import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "reward")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reward extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reward_id")
    private Long id;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /**
     * 보상으로 지급할 금액 (0일 수 있음)
     */
    @Column(name = "money_amount", nullable = false)
    private long moneyAmount = 0L;

    /**
     * 보상으로 지급할 칭호 (선택 사항)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "title_id") // nullable = true (기본값)
    private Title titleToGrant;

    @Builder
    public Reward(String description, long moneyAmount, Title titleToGrant) {
        this.description = description;
        this.moneyAmount = moneyAmount;
        this.titleToGrant = titleToGrant;
    }
}