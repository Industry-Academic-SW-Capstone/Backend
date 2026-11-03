package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.event.ExtraEventOutbox;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 증권사는 랭킹에 보여 줄 티어를 회원 테이블에 복사해 둔다. 점수를 바꾸는 곳은 바꾸기 전 티어를 읽어 두고,
// 바꾼 뒤 달라졌으면 알린다 — 점수가 바뀔 때마다가 아니라 티어가 바뀔 때만 메시지가 나간다.
@Component
@RequiredArgsConstructor
public class MissionTierTracker {

    private final MissionProgressRepository missionProgressRepository;
    private final MissionProgressCalculator missionProgressCalculator;
    private final ExtraEventOutbox extraEventOutbox;

    public String tierOf(Long memberId) {
        int activityScore = scoreTracker(memberId, MissionConditionType.ACTIVITY_SCORE);
        int skillScore = missionProgressCalculator.calculateScoreFromProfit(
                scoreTracker(memberId, MissionConditionType.SKILL_SCORE));
        return MissionTierPolicy.tierOf(activityScore + skillScore);
    }

    public void publishIfChanged(Long memberId, String before) {
        String after = tierOf(memberId);
        if (!after.equals(before)) {
            extraEventOutbox.tierChanged(memberId, after);
        }
    }

    private int scoreTracker(Long memberId, MissionConditionType conditionType) {
        return missionProgressRepository
                .findByMemberAndMissionTypeWithMission(memberId, MissionTrack.ACHIEVEMENT, conditionType)
                .map(MissionProgress::getCurrentValue)
                .orElse(0);
    }
}
