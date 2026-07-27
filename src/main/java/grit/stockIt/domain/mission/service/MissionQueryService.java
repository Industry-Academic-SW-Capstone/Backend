package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.dto.MemberTitleResponse;
import grit.stockIt.domain.mission.dto.MissionDashboardResponse;
import grit.stockIt.domain.mission.dto.MissionListResponse;
import grit.stockIt.domain.mission.dto.UserTierStatusResponse;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * [B-2 분리] 미션 조회 전용 서비스 (읽기 전용 트랜잭션).
 * MissionController GET 4개와 RankingService.getTierForMember가 진입점이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MissionQueryService {

    private final MemberRepository memberRepository;
    private final MissionProgressRepository missionProgressRepository;
    private final MemberTitleRepository memberTitleRepository;
    private final MissionProgressCalculator missionProgressCalculator;

    // 대시보드용 요약 정보 조회
    public MissionDashboardResponse getMissionDashboard(String email) {
        Member member = getMemberByEmail(email);

        // 1. 연속 출석 일수 (업적 중 LOGIN_STREAK 타입의 현재 진행도 조회)
// 7일, 15일, 30일 업적 미션들과 충돌하지 않음
        int streak = missionProgressRepository
                .findTopByMemberAndConditionOrderByGoalDesc(member, MissionTrack.ACHIEVEMENT, MissionConditionType.LOGIN_STREAK)
                .map(MissionProgress::getCurrentValue)
                .orElse(0); // 트래커 미션이 아직 생성 안 됐으면 0일

        // 2. 남은 일일 미션 개수 (DAILY 트랙 중, 완료되지 않은 것의 개수)
        List<MissionProgress> dailyMissions = missionProgressRepository.findAllByMemberAndMission_Track(member, MissionTrack.DAILY);
        int remaining = (int) dailyMissions.stream()
                .filter(mp -> !mp.isCompleted())
                .count();

        return MissionDashboardResponse.builder()
                .consecutiveAttendanceDays(streak)
                .remainingDailyMissions(remaining)
                .build();
    }

    // 트랙별 미션 리스트 조회 (Enum 변환을 통한 안정성 확보)
    public List<MissionListResponse> getMissionsByTrack(String email, String trackName) {
        Member member = getMemberByEmail(email);
        List<MissionProgress> allProgress = missionProgressRepository.findByMemberWithMissionAndReward(member);

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
            log.warn("유효하지 않은 미션 트랙 요청: email={}, track={}", email, trackName);
            return List.of(); // 빈 리스트 반환하여 에러 방지
        }
    }

    // 보유 칭호 목록 조회
    public List<MemberTitleResponse> getMyTitles(String email) {
        Member member = getMemberByEmail(email);
        return memberTitleRepository.findAllByMember(member).stream()
                .map(MemberTitleResponse::new)
                .collect(Collectors.toList());
    }

    public UserTierStatusResponse getTierInfo(String email) {
        Member member = getMemberByEmail(email);

        // 1. 활동 점수
        int activityScore = missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.ACTIVITY_SCORE)
                .map(MissionProgress::getCurrentValue).orElse(0);

        // 2. 실력 점수
        int totalProfit = missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.SKILL_SCORE)
                .map(MissionProgress::getCurrentValue).orElse(0);

        int skillScore = missionProgressCalculator.calculateScoreFromProfit(totalProfit);
        int totalScore = activityScore + skillScore;

        // 3. 티어 계산
        String currentTier;
        String nextTier;
        int nextTierScore;        // 다음 티어 승급 점수 (목표)
        int currentTierStartScore; // [신규] 현재 티어 시작 점수 (진행도 계산용)

        if (totalScore < 800) {
            currentTier = "BRONZE 1";
            nextTier = "BRONZE 2";
            currentTierStartScore = 0;   // 0 ~ 799
            nextTierScore = 800;
        } else if (totalScore < 1000) {
            currentTier = "BRONZE 2";
            nextTier = "BRONZE 3";
            currentTierStartScore = 800; // 800 ~ 999
            nextTierScore = 1000;
        } else if (totalScore < 1200) {
            currentTier = "BRONZE 3";
            nextTier = "SILVER 1";
            currentTierStartScore = 1000; // 1000 ~ 1199
            nextTierScore = 1200;
        } else if (totalScore < 1400) {
            currentTier = "SILVER 1";
            nextTier = "SILVER 2";
            currentTierStartScore = 1200; // 1200 ~ 1399 (신규 유저 시작 구간)
            nextTierScore = 1400;
        } else if (totalScore < 1600) {
            currentTier = "SILVER 2";
            nextTier = "SILVER 3";
            currentTierStartScore = 1400;
            nextTierScore = 1600;
        } else if (totalScore < 1800) {
            currentTier = "SILVER 3";
            nextTier = "GOLD 1";
            currentTierStartScore = 1600;
            nextTierScore = 1800;
        } else if (totalScore < 2000) {
            currentTier = "GOLD 1";
            nextTier = "GOLD 2";
            currentTierStartScore = 1800;
            nextTierScore = 2000;
        } else if (totalScore < 2200) {
            currentTier = "GOLD 2";
            nextTier = "GOLD 3";
            currentTierStartScore = 2000;
            nextTierScore = 2200;
        } else if (totalScore < 2400) {
            currentTier = "GOLD 3";
            nextTier = "MASTER 1";
            currentTierStartScore = 2200;
            nextTierScore = 2400;
        } else if (totalScore < 2600) {
            currentTier = "MASTER 1";
            nextTier = "MASTER 2";
            currentTierStartScore = 2400;
            nextTierScore = 2600;
        } else if (totalScore < 2800) {
            currentTier = "MASTER 2";
            nextTier = "MASTER 3";
            currentTierStartScore = 2600;
            nextTierScore = 2800;
        } else if (totalScore < 3000) {
            currentTier = "MASTER 3";
            nextTier = "GRANDMASTER 1";
            currentTierStartScore = 2800;
            nextTierScore = 3000;
        } else if (totalScore < 3200) {
            currentTier = "GRANDMASTER 1";
            nextTier = "GRANDMASTER 2";
            currentTierStartScore = 3000;
            nextTierScore = 3200;
        } else if (totalScore < 3400) {
            currentTier = "GRANDMASTER 2";
            nextTier = "GRANDMASTER 3";
            currentTierStartScore = 3200;
            nextTierScore = 3400;
        } else if (totalScore < 3600) {
            currentTier = "GRANDMASTER 3";
            nextTier = "LEGEND";
            currentTierStartScore = 3400;
            nextTierScore = 3600;
        } else {
            currentTier = "LEGEND";
            nextTier = "MAX";
            currentTierStartScore = 3600;
            nextTierScore = totalScore; // Legend는 목표치가 없으므로 현재 점수와 동일시
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

    public List<MissionProgress> getMissionProgressList(String email) {
        Member member = getMemberByEmail(email);
        return missionProgressRepository.findByMemberWithMissionAndReward(member);
    }

    // getMemberByEmail은 B-6 방침과 동일하게 private 복제 (과추상화 금지)
    private Member getMemberByEmail(String email) {
        return memberRepository.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다. Email: " + email));
    }
}
