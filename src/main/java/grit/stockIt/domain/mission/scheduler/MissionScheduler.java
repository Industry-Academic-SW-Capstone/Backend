package grit.stockIt.domain.mission.scheduler;

import grit.stockIt.domain.mission.service.MissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MissionScheduler {

    private final MissionService missionService;

    /**
     * [기존] 매일 자정 (00:00)에 일일 미션을 초기화합니다.
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Seoul")
    public void dailyMissionResetTask() {
        log.info("=== 일일 미션 초기화 스케줄러 시작 ===");
        try {
            missionService.resetDailyMissions();
            log.info("=== 일일 미션 초기화 완료 ===");
        } catch (Exception e) {
            log.error("일일 미션 초기화 중 오류 발생", e);
        }
    }

    /**
     * [신규] 매일 자정 (00:00)에 '홀딩' 미션 진행도를 1 증가시킵니다.
     * (주의: 일일 미션 초기화와 동시에 실행되므로 트랜잭션 충돌 방지를 위해 1분 뒤 실행하거나 순차 실행 권장)
     * 여기서는 cron을 동일하게 두되, 서비스 내부에서 트랜잭션을 분리 처리합니다.
     */
    @Scheduled(cron = "0 1 0 * * ?", zone = "Asia/Seoul") // 00시 01분에 실행 (안전하게)
    public void dailyHoldingProgressTask() {
        log.info("=== 홀딩 미션 진행도 업데이트 스케줄러 시작 ===");
        try {
            missionService.processDailyHoldingUpdate();
            log.info("=== 홀딩 미션 진행도 업데이트 완료 ===");
        } catch (Exception e) {
            log.error("홀딩 미션 업데이트 중 오류 발생", e);
        }
    }
}