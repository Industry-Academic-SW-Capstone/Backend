package grit.stockIt.domain.mission.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MissionResponse {
    private List<MissionProgressDto> dailyMissions;
    private List<MissionProgressDto> weeklyMissions;
}