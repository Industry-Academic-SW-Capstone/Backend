package grit.stockIt.domain.mission.dto;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.enums.MissionTrack;
import lombok.Getter;

@Getter
public class MissionListDto {
    private Long id;
    private String track;
    private String title;
    private String description; // UI에 표시될 설명
    private int currentValue;
    private int goalValue;
    private boolean isCompleted;

    // 보상 정보
    private long rewardMoney;
    private String rewardTitle;

    public MissionListDto(MissionProgress progress) {
        Mission m = progress.getMission();
        Reward r = m.getReward();

        this.id = m.getId();
        this.track = m.getTrack().name();
        this.title = m.getName();

        // ⭐️ [핵심 로직 수정] ⭐️
        // 1. 업적(ACHIEVEMENT)이고 칭호 보상이 있다면 -> 칭호의 설명(Title.description)을 가져옴
        if (m.getTrack() == MissionTrack.ACHIEVEMENT && r != null && r.getTitleToGrant() != null) {
            this.description = r.getTitleToGrant().getDescription();
        }
        // 2. 일반 미션이라면 -> 미션 이름(name)으로도 충분하므로 그대로 사용 (또는 빈 문자열)
        else {
            this.description = m.getName();
        }

        this.currentValue = progress.getCurrentValue();
        this.goalValue = m.getGoalValue();
        this.isCompleted = progress.isCompleted();

        if (r != null) {
            this.rewardMoney = r.getMoneyAmount();
            this.rewardTitle = (r.getTitleToGrant() != null) ? r.getTitleToGrant().getName() : null;
        }
    }
}