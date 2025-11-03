package grit.stockIt.domain.user.entity;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class UserMission {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mission_id")
    private Mission mission;

    private int currentCount = 0;  // 현재 진행 횟수

    private boolean isCompleted = false;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @Enumerated(EnumType.STRING)
    private MissionStatus status = MissionStatus.IN_PROGRESS;

    // 미션 상태 enum
    public enum MissionStatus {
        IN_PROGRESS, COMPLETED, REWARD_CLAIMED, FAILED
    }

    // 생성자
    public UserMission(User user, Mission mission) {
        this.user = user;
        this.mission = mission;
        this.startedAt = LocalDateTime.now();
    }

    // 미션 진행 상태 업데이트
    public void updateProgress() {
        this.currentCount++;
        if (this.currentCount >= this.mission.getRequiredCount()) {
            this.complete();
        }
    }

    // 보상 수령 처리를 위한 메소드 추가
    public void setRewardClaimed() {
        if (this.status != MissionStatus.COMPLETED) {
            throw new IllegalStateException("완료된 미션만 보상을 수령할 수 있습니다.");
        }
        this.status = MissionStatus.REWARD_CLAIMED;
    }

    // 미션 완료 처리
    private void complete() {
        this.isCompleted = true;
        this.status = MissionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }
}