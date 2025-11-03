package grit.stockIt.domain.mission.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ClaimRewardRequest {
    private Long missionId; // 보상을 수령할 미션 ID
}