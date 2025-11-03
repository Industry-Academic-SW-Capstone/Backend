package grit.stockIt.domain.mission.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class UserMissionProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long userMissionProgressId;

    // TODO: User 엔티티와 @ManyToOne으로 연결해야 함 (여기서는 임시로 userId 사용)
    @Column(nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "missionId", nullable = false)
    private Mission mission;

    private int currentProgress = 0; // 현재 진행 횟수

    private boolean isCompleted = false; // 완료 여부

    private boolean isRewardClaimed = false; // 보상 수령 여부

    @Column(nullable = false)
    private LocalDateTime expiresAt; // 이 미션 주기의 만료 시각

    // 생성자 (새로운 미션 주기 시작 시)
    public UserMissionProgress(Long userId, Mission mission, LocalDateTime expiresAt) {
        this.userId = userId;
        this.mission = mission;
        this.expiresAt = expiresAt;
    }
}