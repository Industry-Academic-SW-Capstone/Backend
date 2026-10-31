package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.dto.MemberTitleResponse;
import grit.stockIt.domain.mission.dto.MissionDashboardResponse;
import grit.stockIt.domain.mission.dto.MissionListResponse;
import grit.stockIt.domain.mission.dto.UserTierStatusResponse;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * [B-2 분리] 미션 조회 전용 서비스 (읽기 전용 트랜잭션).
 * MissionController GET 4개가 진입점이다. 회원은 로그인 토큰의 회원 번호로만 가리킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionQueryService {

    private final MissionProgressRepository missionProgressRepository;
    private final MemberTitleRepository memberTitleRepository;
    private final MissionProgressCalculator missionProgressCalculator;

    // 대시보드용 요약 정보 조회
    public MissionDashboardResponse getMissionDashboard(Long memberId) {

        // 1. 연속 출석 일수 (업적 중 LOGIN_STREAK 타입의 현재 진행도 조회)
// 7일, 15일, 30일 업적 미션들과 충돌하지 않음
        int streak = missionProgressRepository
                .findTopByMemberAndConditionOrderByGoalDesc(memberId, MissionTrack.ACHIEVEMENT, MissionConditionType.LOGIN_STREAK)
                .map(MissionProgress::getCurrentValue)
                .orElse(0); // 트래커 미션이 아직 생성 안 됐으면 0일

        // 2. 남은 일일 미션 개수 (DAILY 트랙 중, 완료되지 않은 것의 개수)
        List<MissionProgress> dailyMissions = missionProgressRepository.findAllByMemberIdAndMission_Track(memberId, MissionTrack.DAILY);
        int remaining = (int) dailyMissions.stream()
                .filter(mp -> !mp.isCompleted())
                .count();

        return MissionDashboardResponse.builder()
                .consecutiveAttendanceDays(streak)
                .remainingDailyMissions(remaining)
                .build();
    }

    // 트랙별 미션 리스트 조회 (Enum 변환을 통한 안정성 확보)
    public List<MissionListResponse> getMissionsByTrack(Long memberId, String trackName) {
        List<MissionProgress> allProgress = missionProgressRepository.findByMemberWithMissionAndReward(memberId);

        // 1. "ALL"인 경우 전체 반환 (대소문자 무시: all, ALL 등)
        if (trackName == null || "ALL".equalsIgnoreCase(trackName)) {
            return allProgress.stream()
                    .map(MissionListResponse::new)
                    .collect(Collectors.toList());
        }

        // 2. 특정 트랙 필터링 (Enum 변환 시도)
        try {
            // 입력값을 대문자로 변환하여 Enum 매핑 (daily -> DAILY)
            MissionTrack filterTrack = MissionTrack.valueOf(trackName.toUpperCase());

            return allProgress.stream()
                    .filter(mp -> mp.getMission().getTrack() == filterTrack) // Enum 타입 비교 (==)
                    .map(MissionListResponse::new)
                    .collect(Collectors.toList());

        } catch (IllegalArgumentException e) {
            // 정의되지 않은 트랙 이름이 들어온 경우 (예: "ABCD")
            log.warn("유효하지 않은 미션 트랙 요청: memberId={}, track={}", memberId, trackName);
            return List.of(); // 빈 리스트 반환하여 에러 방지
        }
    }

    // 보유 칭호 목록 조회
    public List<MemberTitleResponse> getMyTitles(Long memberId) {
        return memberTitleRepository.findAllByMemberId(memberId).stream()
                .map(MemberTitleResponse::new)
                .collect(Collectors.toList());
    }

    public UserTierStatusResponse getTierInfo(Long memberId) {

        // 1. 활동 점수
        int activityScore = missionProgressRepository.findByMemberAndMissionTypeWithMission(memberId, MissionTrack.ACHIEVEMENT, MissionConditionType.ACTIVITY_SCORE)
                .map(MissionProgress::getCurrentValue).orElse(0);

        // 2. 실력 점수
        int totalProfit = missionProgressRepository.findByMemberAndMissionTypeWithMission(memberId, MissionTrack.ACHIEVEMENT, MissionConditionType.SKILL_SCORE)
                .map(MissionProgress::getCurrentValue).orElse(0);

        int skillScore = missionProgressCalculator.calculateScoreFromProfit(totalProfit);
        int totalScore = activityScore + skillScore;

        // 3. 티어 계산
        int tierIndex = MissionTierPolicy.indexOf(totalScore);
        String currentTier = MissionTierPolicy.band(tierIndex).name();
        int currentTierStartScore = MissionTierPolicy.band(tierIndex).startScore(); // 진행도 계산용
        String nextTier;
        int nextTierScore; // 다음 티어 승급 점수 (목표)
        if (MissionTierPolicy.isLast(tierIndex)) {
            nextTier = "MAX";
            nextTierScore = totalScore; // Legend는 목표치가 없으므로 현재 점수와 동일시
        } else {
            nextTier = MissionTierPolicy.band(tierIndex + 1).name();
            nextTierScore = MissionTierPolicy.band(tierIndex + 1).startScore();
        }

        // 4. 진행도 계산 (현재 구간 내에서의 %)
        double progress = 0.0;
        if (!"MAX".equals(nextTier)) {
            // 공식: (현재점수 - 시작점수) / (목표점수 - 시작점수) * 100
            double gainedInCurrentTier = (double) (totalScore - currentTierStartScore);
            double rangeOfCurrentTier = (double) (nextTierScore - currentTierStartScore);

            if (rangeOfCurrentTier > 0) {
                progress = (gainedInCurrentTier / rangeOfCurrentTier) * 100.0;
            }
        } else {
            progress = 100.0;
        }

        return UserTierStatusResponse.builder()
                .currentTier(currentTier)
                .nextTier(nextTier)
                .totalScore(totalScore)
                .activityScore(activityScore)
                .skillScore(skillScore)
                .scoreToNextTier(nextTierScore - totalScore)
                .progressPercentage(progress)
                .build();
    }

    public List<MissionProgress> getMissionProgressList(Long memberId) {
        return missionProgressRepository.findByMemberWithMissionAndReward(memberId);
    }
}
