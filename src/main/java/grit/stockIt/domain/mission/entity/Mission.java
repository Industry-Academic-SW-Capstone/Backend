package grit.stockIt.domain.mission.entity;

import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.enums.MissionType;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "mission")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mission_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    /**
     * 미션의 최상위 분류 (DAILY, SHORT_TERM, ACHIEVEMENT 등)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "track", nullable = false, length = 30)
    private MissionTrack track;

    /**
     * 미션의 단계 (COMMON, INTERMEDIATE, ADVANCED)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private MissionType type;

    /**
     * 미션 완료 조건 (BUY_COUNT, LOGIN_COUNT 등)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "condition_type", nullable = false, length = 50)
    private MissionConditionType conditionType;

    /**
     * 목표 달성 수치 (예: 10회, 10000원)
     */
    @Column(name = "goal_value", nullable = false)
    private int goalValue;

    /**
     * 미션 완료 시 지급할 보상
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reward_id")
    private Reward reward;

    /**
     * 연계되는 다음 단계 미션 (중급 -> 고급 등)
     * (1:1 셀프 참조)
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "next_mission_id")
    private Mission nextMission;

    @Builder
    public Mission(String name, String description, MissionTrack track, MissionType type,
                   MissionConditionType conditionType, int goalValue, Reward reward, Mission nextMission) {
        this.name = name;
        this.description = description;
        this.track = track;
        this.type = type;
        this.conditionType = conditionType;
        this.goalValue = goalValue;
        this.reward = reward;
        this.nextMission = nextMission;
    }
}