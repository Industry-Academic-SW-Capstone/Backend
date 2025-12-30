package grit.stockIt.domain.mission.dto;

import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import lombok.Getter;

@Getter
public class MissionProgressResponseDto {

    private final Long missionId;
    private final String name;
    private final String description;
    private final MissionTrack track; // "DAILY", "SHORT_TERM" 등 (그룹화용)
    private final MissionStatus status; // "IN_PROGRESS", "COMPLETED" (버튼 활성화/비활성화용)
    private final int currentValue;
    private final int goalValue;
    private final String rewardDescription; // 보상 미리보기 (예: "100원", "칭호: 초보")

    // Service에서 Entity를 DTO로 변환하기 위한 생성자
    public MissionProgressResponseDto(MissionProgress progress) {
        this.missionId = progress.getMission().getId();
        this.name = progress.getMission().getName();
        this.description = progress.getMission().getDescription();
        this.track = progress.getMission().getTrack();
        this.status = progress.getStatus();
        this.currentValue = progress.getCurrentValue();
        this.goalValue = progress.getMission().getGoalValue();
        this.rewardDescription = progress.getMission().getReward().getDescription();
    }
}