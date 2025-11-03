package grit.stockIt.domain.mission.entity;

import grit.stockIt.domain.reward.entity.Reward;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Mission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long missionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MissionType missionType;

    public boolean isDaily() {
        return missionType == MissionType.DAILY;
    }


    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ValidationType validationType;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(nullable = false)
    private int requiredCount = 1;

    // rewardId를 실제 Reward 엔티티와의 관계로 변경
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_id")
    private Reward reward;

    // 미션의 활성화 여부
    private boolean isActive = true;
}