package grit.stockIt.domain.mission.dto;

import grit.stockIt.domain.mission.entity.Reward;
import lombok.Getter;

@Getter
public class RewardResponseDto {

    private final String message;
    private final long moneyAmount; // 지급된 돈 (0일 수 있음)
    private final String grantedTitleName; // 획득한 칭호 이름 (null일 수 있음)

    // Service에서 Entity를 DTO로 변환하기 위한 생성자
    public RewardResponseDto(Reward reward) {
        this.message = "보상이 지급되었습니다!";
        this.moneyAmount = reward.getMoneyAmount();
        this.grantedTitleName = (reward.getTitleToGrant() != null)
                ? reward.getTitleToGrant().getName()
                : null;
    }

/*    // 보상이 없는 경우 (예: 업적)
    public RewardResponseDto(String customMessage) {
        this.message = customMessage;
        this.moneyAmount = 0;
        this.grantedTitleName = null;
    }*/
}