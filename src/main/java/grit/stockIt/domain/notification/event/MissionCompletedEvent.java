package grit.stockIt.domain.notification.event;

import grit.stockIt.domain.mission.enums.MissionTrack;

// 미션 완료 이벤트
public record MissionCompletedEvent(
        Long memberId,
        Long missionId,
        String missionName,
        MissionTrack track,
        Long rewardId,
        Long moneyAmount,  // 보상 금액 (0일 수 있음)
        String titleName    // 보상 칭호 이름 (null 가능)
) {
}

