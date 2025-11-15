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
     * 매일 자정 (00:00)에 일일 미션을 초기화합니다.
     * (cron = "초 분 시 일 월 요일")
     * zone = "Asia/Seoul" : 서버의 시간대와 관계없이 항상 한국 시간 기준 자정에 실행
     */
    @Scheduled(cron = "0 0 0 * * ?", zone = "Asia/Seoul")
    public void dailyMissionResetTask() {
        log.info("=== 일일 미션 초기화 스케줄러 시작 ===");
        try {
            missionService.resetDailyMissions();
            log.info("=== 일일 미션 초기화 스케줄러 완료 ===");
        } catch (Exception e) {
            log.error("일일 미션 초기화 스케줄러 실행 중 오류 발생", e);
        }
    }
}