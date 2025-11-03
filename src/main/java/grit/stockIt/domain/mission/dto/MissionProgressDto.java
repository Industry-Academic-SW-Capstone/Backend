package grit.stockIt.domain.mission.dto;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.UserMissionProgress;
import lombok.Getter;

@Getter
public class MissionProgressDto {
    private Long missionId;
    private String title;
    private int requiredCount;
    private int currentProgress;
    private boolean isCompleted;
    private boolean isRewardClaimed;

    // 1. (진행도 O) 미션 원본 + 사용자 진행도 DTO 생성
    public MissionProgressDto(Mission mission, UserMissionProgress progress) {
        this.missionId = mission.getMissionId();
        this.title = mission.getTitle();
        this.requiredCount = mission.getRequiredCount();
        this.currentProgress = progress.getCurrentProgress();
        this.isCompleted = progress.isCompleted();
        this.isRewardClaimed = progress.isRewardClaimed();
    }

    // 2. (진행도 X) 미션 원본만으로 DTO 생성 (아직 시작 안 한 미션)
    public MissionProgressDto(Mission mission) {
        this.missionId = mission.getMissionId();
        this.title = mission.getTitle();
        this.requiredCount = mission.getRequiredCount();
        this.currentProgress = 0;
        this.isCompleted = false;
        this.isRewardClaimed = false;
    }
}