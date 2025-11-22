package grit.stockIt.domain.mission.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MissionDashboardDto {
    private int consecutiveAttendanceDays; // 연속 출석 일수
    private int remainingDailyMissions;    // 남은 일일 미션 개수
}