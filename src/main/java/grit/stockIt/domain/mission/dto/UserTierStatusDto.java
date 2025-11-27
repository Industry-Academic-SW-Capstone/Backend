package grit.stockIt.domain.mission.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UserTierStatusDto {
    private String currentTier;      // BRONZE, SILVER, GOLD, PLATINUM, LEGEND
    private String nextTier;         // 다음 티어 (LEGEND면 null 혹은 "MAX")

    private int totalScore;          // 총점
    private int activityScore;       // 활동 점수
    private int skillScore;          // 실력 점수

    private int scoreToNextTier;     // 다음 티어까지 남은 점수
    private double progressPercentage; // 티어 달성 진행도 (%)
}