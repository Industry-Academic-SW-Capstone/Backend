package grit.stockIt.domain.mission.entity;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "mission_progress",
        // 한 명의 유저가 동일한 미션에 대해 중복된 진행도를 가지는 것을 방지
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "mission_progress_uk",
                        columnNames = {"member_id", "mission_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MissionProgress extends BaseEntity { // 생성 시간(createdAt) = 획득 시간

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mission_progress_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id", nullable = false)
    private Mission mission;

    /**
     * 현재 진행 수치 (예: 3 / 10회)
     */
    @Column(name = "current_value", nullable = false)
    private int currentValue = 0;

    /**
     * 미션 진행 상태 (INACTIVE, IN_PROGRESS, COMPLETED)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MissionStatus status;

    @Builder
    public MissionProgress(Member member, Mission mission, int currentValue, MissionStatus status) {
        this.member = member;
        this.mission = mission;
        this.currentValue = currentValue;
        this.status = status;
    }

    // --- 연관관계 편의 메서드 ---

    /**
     * Member 엔티티에서 호출하여 양방향 관계를 설정합니다. (Member의 addMissionProgress)
     */
    public void setMember(Member member) {
        this.member = member;
    }

    // --- 핵심 비즈니스 로직 메서드 (Service에서 호출) ---

    /**
     * 미션 진행도를 증가시킵니다. (이벤트 발생 시)
     */
    public void incrementProgress(int value) {
        // 이미 완료된 미션은 갱신하지 않음
        if (this.status == MissionStatus.COMPLETED) {
            return;
        }
        this.currentValue += value;
    }

    /**
     * 미션 완료 여부를 확인합니다.
     * @return 목표 달성 시 true
     */
    public boolean isCompleted() {
        return this.currentValue >= this.mission.getGoalValue();
    }

    /**
     * 미션을 '진행 중' 상태로 활성화합니다. (다음 단계 미션 열기)
     */
    public void activate() {
        this.status = MissionStatus.IN_PROGRESS;
    }

    /**
     * 미션을 '비활성' 상태로 만듭니다. (트랙 초기화 시)
     */
    public void deactivate() {
        this.status = MissionStatus.INACTIVE;
        this.currentValue = 0; // 비활성화 시 진행도도 초기화
    }

    /**
     * 미션을 '완료' 상태로 변경합니다.
     */
    public void complete() {
        this.status = MissionStatus.COMPLETED;
        // currentValue를 goalValue로 고정시킬 수 있음 (선택 사항)
        // this.currentValue = this.mission.getGoalValue();
    }

    /**
     * 미션을 초기화합니다. (일일 미션용)
     */
    public void reset() {
        this.currentValue = 0;
        // 일일 미션은 비활성이 아닌 '진행 중' 상태로 바로 초기화
        this.status = MissionStatus.IN_PROGRESS;
    }
}