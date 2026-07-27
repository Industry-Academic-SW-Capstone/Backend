package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.repository.MissionProgressRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [B-5 분리] 스케줄러 호출용 배치 서비스 (자정 초기화·홀딩 일수 갱신).
 * - 진입점: MissionScheduler
 * - 의존: 리포지토리 + MissionRewardService(단방향, 순환 금지)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional // 미션 관련 로직은 하나의 트랜잭션으로 관리 (기존 MissionService와 동일 시맨틱스)
public class MissionBatchService {

    private final MissionProgressRepository missionProgressRepository;
    private final AccountStockRepository accountStockRepository;
    private final MissionRewardService missionRewardService;

    /**
     * [신규] 스케줄러 호출용: 매일 자정에 보유 주식이 있으면 홀딩 일수 +1
     */
    public void processDailyHoldingUpdate() {
        // 1. 'HOLDING_DAYS' 조건이면서 '진행 중'인 미션들만 조회
        List<MissionProgress> holdingProgressList = missionProgressRepository
                .findAllByMission_ConditionTypeAndStatus(MissionConditionType.HOLDING_DAYS, MissionStatus.IN_PROGRESS);

        for (MissionProgress progress : holdingProgressList) {
            Member member = progress.getMember();

            // 2. 회원이 주식을 하나라도 가지고 있는지 확인 (수량 > 0)
            boolean hasStock = accountStockRepository.existsByAccount_MemberAndQuantityGreaterThan(member, 0);

            if (hasStock) {
                progress.incrementProgress(1);
                log.info("홀딩 미션 +1일 증가: MemberId={}, MissionId={}, NewValue={}",
                        member.getMemberId(), progress.getMission().getId(), progress.getCurrentValue());
                missionRewardService.checkMissionCompletion(progress);
            }
        }
    }

    /**
     * [리팩토링] 연속 출석 초기화 로직
     * - 타입 안전성을 위해 Enum 상수를 직접 인자로 전달합니다.
     */
    public void checkAndResetAttendanceStreaks() {
        log.info("연속 출석 끊김 여부 확인 및 초기화 시작 (Bulk Update)...");

        // 변경된 메서드 시그니처에 맞춰 Enum 값 전달
        int updatedCount = missionProgressRepository.bulkResetLoginStreakForAbsentees(
                MissionTrack.ACHIEVEMENT,           // :streakTrack (업적 트랙)
                MissionConditionType.LOGIN_STREAK,  // :streakCondition (연속 출석 체크용)
                MissionTrack.DAILY,                 // :dailyTrack (일일 미션 트랙)
                MissionConditionType.LOGIN_COUNT,   // :dailyCondition (일일 출석 여부 확인용)
                MissionStatus.COMPLETED             // :completedStatus (완료 상태 기준)
        );

        log.info("총 {}건의 연속 출석 기록이 일괄 초기화되었습니다.", updatedCount);
    }

    public void resetDailyMissions() {
        log.info("일일 미션 전체 초기화 시작...");
        List<MissionProgress> dailyProgressList = missionProgressRepository.findAllByMission_Track(MissionTrack.DAILY);
        for (MissionProgress progress : dailyProgressList) {
            progress.reset();
        }

        // 2. [신규] Track = ACHIEVEMENT 이지만 'DAILY_TRADE_COUNT' 타입인 미션(카이팅 장인) 초기화
        // (완료하지 못한 경우에만 리셋해야 함)
        List<MissionProgress> kitingMissions = missionProgressRepository
                .findAllByMission_ConditionTypeAndStatus(MissionConditionType.DAILY_TRADE_COUNT, MissionStatus.IN_PROGRESS);

        for (MissionProgress mp : kitingMissions) {
            // 업적이라 트랙은 ACHIEVEMENT지만 성격은 Daily이므로 매일 리셋
            mp.setCurrentValue(0);
        }
        log.info("일일 미션 총 {}건 초기화 완료.", dailyProgressList.size());
    }
}
