package grit.stockIt.domain.mission.dto;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import lombok.Getter;

@Getter
public class MissionListDto {
    private Long id;
    private String track;          // DAILY, ACHIEVEMENT, SHORT_TERM...
    private String title;          // 미션 이름
    private String description;    // 설명 (UI에는 안보여도 툴팁용)
    private int currentValue;      // 현재 진행도
    private int goalValue;         // 목표치
    private boolean isCompleted;   // 완료 여부

    // 보상 정보
    private long rewardMoney;      // 보상금액 (없으면 0)
    private String rewardTitle;    // 보상칭호명 (없으면 null)

    public MissionListDto(MissionProgress progress) {
        Mission m = progress.getMission();
        Reward r = m.getReward();

        this.id = m.getId();
        this.track = m.getTrack().name();
        this.title = m.getName();
        this.description = "설명 생략"; // 필요 시 m.getDescription() 추가
        this.currentValue = progress.getCurrentValue();
        this.goalValue = m.getGoalValue();
        this.isCompleted = progress.isCompleted();

        if (r != null) {
            this.rewardMoney = r.getMoneyAmount();
            this.rewardTitle = (r.getTitleToGrant() != null) ? r.getTitleToGrant().getName() : null;
        }
    }
}