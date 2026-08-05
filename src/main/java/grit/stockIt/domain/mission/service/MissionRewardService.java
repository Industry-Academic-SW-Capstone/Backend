package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.enums.MissionType;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * [B-3 분리] 미션 완료 판정·보상 지급·체인 처리 서비스.
 * 의존은 리포지토리 + B-1 순수 클래스 + 자기 자신뿐인 sink — 다른 미션 서비스를 주입하지 않는다(순환 금지).
 * 완료 체인의 재진입(checkMissionCompletion -> handleMissionChain -> updateSpecificAchievement ->
 * checkMissionCompletion)은 전부 이 클래스 내부 자기호출로 닫힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional // 미션 관련 로직은 하나의 트랜잭션으로 관리 (기존 MissionService와 동일 시맨틱스)
public class MissionRewardService {

    private final MissionRepository missionRepository;
    private final MissionProgressRepository missionProgressRepository;
    private final MemberTitleRepository memberTitleRepository;
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MissionTrackPolicy missionTrackPolicy;

    private static final int MISSION_COMPLETION_ACTIVITY_POINTS = 10;

    public void checkMissionCompletion(MissionProgress progress) {
        if (progress.getStatus() == MissionStatus.COMPLETED || !progress.isCompleted()) {
            return;
        }

        progress.complete();
        log.info("미션 완료: MemberId={}, MissionId={}", progress.getMember().getMemberId(), progress.getMission().getId());

        Mission mission = progress.getMission();
        Reward reward = mission.getReward();

        // 보상 지급
        distributeReward(progress.getMember(), reward);

        // 출석 미션은 알림 발송 제외
        if (mission.getConditionType() != MissionConditionType.LOGIN_COUNT) {
            // 미션 완료 알림 이벤트 발행
            publishMissionCompletedEvent(progress.getMember(), mission, reward);
        }

        activateNextMission(progress);
        handleMissionChain(progress);
        checkSeedCopierAchievement(progress.getMember());

        // [추가] 3. 활동 점수(Activity Score) 반영
        updateActivityScore(progress.getMember());
    }

    // 레전드 미션(903) 달성 체크
    public void checkLegendTier(Member member, int totalScore) {
        if (totalScore >= MissionTierPolicy.LEGEND_THRESHOLD_SCORE) { // Legend 기준 점수
            missionRepository.findAllByTrackAndConditionType(MissionTrack.ACHIEVEMENT, MissionConditionType.REACH_LEGEND)
                    .stream().findFirst().ifPresent(legendMission -> {
                        missionProgressRepository.findByMemberAndMission(member, legendMission)
                                .ifPresent(progress -> {
                                    if (!progress.isCompleted()) {
                                        progress.setCurrentValue(1);
                                        checkMissionCompletion(progress); // 칭호 및 1억 지급
                                    }
                                });
                    });
        }
    }

    // applyForBankruptcy(B-6 잔류) 등 완료 판정 없이 보상만 지급하는 경로가 있어 public
    public void distributeReward(Member member, Reward reward) {
        if (reward == null) return;

        if (reward.getMoneyAmount() > 0) {
            accountRepository.findByMemberAndIsDefaultTrue(member)
                    .ifPresentOrElse(
                            acc -> {
                                acc.increaseCash(BigDecimal.valueOf(reward.getMoneyAmount()));
                                log.info("보상 지급: {}원", reward.getMoneyAmount());
                            },
                            () -> log.error("보상 지급 실패: 기본 계좌 없음 MemberId={}", member.getMemberId())
                    );
        }

        if (reward.getTitleToGrant() != null) {
            if (!memberTitleRepository.existsByMemberAndTitle(member, reward.getTitleToGrant())) {
                member.addMemberTitle(MemberTitle.builder()
                        .member(member)
                        .title(reward.getTitleToGrant())
                        .build());
                log.info("칭호 지급: {}", reward.getTitleToGrant().getName());
            }
        }
    }

    /**
     * 미션 완료 알림 이벤트 발행
     */
    private void publishMissionCompletedEvent(Member member, Mission mission, Reward reward) {
        Long rewardId = reward != null ? reward.getId() : null;
        Long moneyAmount = reward != null ? reward.getMoneyAmount() : 0L;
        String titleName = (reward != null && reward.getTitleToGrant() != null)
                ? reward.getTitleToGrant().getName()
                : null;

        MissionCompletedEvent event = new MissionCompletedEvent(
                member.getMemberId(),
                mission.getId(),
                mission.getName(),
                mission.getTrack(),
                rewardId,
                moneyAmount,
                titleName
        );

        eventPublisher.publishEvent(event);
        log.debug("미션 완료 이벤트 발행: memberId={}, missionId={}, missionName={}",
                member.getMemberId(), mission.getId(), mission.getName());
    }

    private void activateNextMission(MissionProgress completedProgress) {
        Mission completedMission = completedProgress.getMission();
        Member member = completedProgress.getMember();

        if (completedMission.getTrack() == MissionTrack.DAILY || completedMission.getTrack() == MissionTrack.ACHIEVEMENT) {
            return;
        }

        Mission nextMission = completedMission.getNextMission();

        if (nextMission != null) {
            log.info("다음 미션 활성화: MissionId={}", nextMission.getId());
            missionProgressRepository.findByMemberAndMission(member, nextMission)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(nextMission)
                                .status(MissionStatus.INACTIVE)
                                .build();
                        member.addMissionProgress(newProgress);
                        return newProgress;
                    }).activate();
        } else if (completedMission.getType() == MissionType.ADVANCED) {
            log.info("트랙 최종 완료: Track={}", completedMission.getTrack());
            resetMissionTrack(member, completedMission.getTrack());
        }
    }

    private void resetMissionTrack(Member member, MissionTrack track) {
        log.info("트랙 초기화 시작: MemberId={}, Track={}", member.getMemberId(), track);
        List<MissionProgress> progressList = missionProgressRepository.findAllByMemberAndMission_Track(member, track);

        for (MissionProgress progress : progressList) {
            progress.reset();
            progress.deactivate();

            // 트랙의 첫 번째 미션(중급 1단계)만 다시 활성화
            if (progress.getMission().getType() == MissionType.INTERMEDIATE
                    && missionTrackPolicy.isFirstMissionInTrack(progress.getMission().getId())) {
                progress.activate();
                log.info("트랙 첫 미션 재활성화: MissionId={}", progress.getMission().getId());
            }
        }
    }

    /**
     * [신규] 활동 점수 업데이트
     * - 미션 1개 완료 시 +10점 (예시)
     * - 최대 점수(GoalValue)를 넘을 수 없음
     */
    private void updateActivityScore(Member member) {
        // 활동 점수 트래커 조회 (ID 998 or Type=ACTIVITY_SCORE)
        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.ACTIVITY_SCORE)
                .ifPresent(tracker -> {
                    int maxScore = tracker.getMission().getGoalValue(); // 예: 1000점
                    int currentScore = tracker.getCurrentValue();

                    if (currentScore < maxScore) {
                        int pointsToAdd = MISSION_COMPLETION_ACTIVITY_POINTS; // 미션 당 점수 (기획에 따라 조정)
                        int newScore = Math.min(currentScore + pointsToAdd, maxScore);
                        tracker.setCurrentValue(newScore);
                        log.info("활동 점수 획득: Member={}, Current={}, Max={}", member.getName(), newScore, maxScore);
                    }
                });
    }

    /**
     * [신규] 시드 복사기 (누적 미션 30회) 체크
     */
    private void checkSeedCopierAchievement(Member member) {
        // 완료된 미션 총 개수 조회
        long completedCount = missionProgressRepository.countByMemberAndStatus(member, MissionStatus.COMPLETED);

        missionProgressRepository.findByMemberAndMissionTypeWithMission(member, MissionTrack.ACHIEVEMENT, MissionConditionType.TOTAL_MISSION_COUNT)
                .ifPresent(progress -> {
                    if (!progress.isCompleted()) {
                        // 현재 완료 횟수를 진행도에 반영
                        progress.setCurrentValue((int) completedCount);
                        if (completedCount >= progress.getMission().getGoalValue()) { // 30회
                            // 재귀 호출 방지를 위해 직접 complete 호출 (혹은 checkMissionCompletion 호출)
                            // 여기서는 간단히 내부 로직 실행
                            progress.complete();
                            distributeReward(member, progress.getMission().getReward());
                            log.info("'시드 복사기' 업적 달성! 총 완료 미션: {}개", completedCount);
                        }
                    }
                });
    }

    private void handleMissionChain(MissionProgress completedProgress) {
        Member member = completedProgress.getMember();
        Mission mission = completedProgress.getMission();

        // 일일 출석 완료 시 -> 연속 출석 업적 갱신
        if (mission.getTrack() == MissionTrack.DAILY &&
                mission.getConditionType() == MissionConditionType.LOGIN_COUNT) {
            log.info("연쇄 업적 갱신 시도: 일일 출석 -> 연속 출석");
            updateSpecificAchievement(member, MissionConditionType.LOGIN_STREAK, 1);
        }
    }

    private void updateSpecificAchievement(Member member, MissionConditionType conditionType, int valueToIncrease) {
        // 1. Optional -> List로 변경하여 해당 타입의 모든 업적 조회 (예: 3일, 7일, 30일 연속 등)
        List<Mission> achievements = missionRepository
                .findAllByTrackAndConditionType(MissionTrack.ACHIEVEMENT, conditionType);

        if (achievements.isEmpty()) return;

        // 2. 조회된 모든 업적에 대해 진행도 업데이트 반복
        for (Mission achievement : achievements) {
            MissionProgress achievementProgress = missionProgressRepository
                    .findByMemberAndMission(member, achievement)
                    .orElseGet(() -> {
                        MissionProgress newProgress = MissionProgress.builder()
                                .member(member)
                                .mission(achievement)
                                .status(MissionStatus.IN_PROGRESS)
                                .build();
                        member.addMissionProgress(newProgress);
                        return newProgress;
                    });

            // 이미 완료된 업적은 패스
            if (achievementProgress.getStatus() != MissionStatus.COMPLETED) {
                achievementProgress.incrementProgress(valueToIncrease);
                log.info("업적 미션({}) 갱신: MissionId={}, NewValue={}",
                        achievement.getName(), achievement.getId(), achievementProgress.getCurrentValue());

                checkMissionCompletion(achievementProgress);
            }
        }
    }
}
