package grit.stockIt.domain.notification.scheduler;

import grit.stockIt.domain.notification.service.MarketNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketNotificationScheduler {

    private final MarketNotificationService marketNotificationService;

    // 장 시작 알림 (평일 09:00)
    @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Seoul")
    public void sendMarketOpenNotification() {
        log.info("=== 장 시작 알림 스케줄러 실행 ===");
        try {
            marketNotificationService.sendMarketOpenNotification();
        } catch (Exception e) {
            log.error("장 시작 알림 전송 중 오류 발생", e);
        }
    }

    // 장 마감 30분 전 알림 (평일 15:00)
    @Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Seoul")
    public void sendMarketCloseReminderNotification() {
        log.info("=== 장 마감 30분 전 알림 스케줄러 실행 ===");
        try {
            marketNotificationService.sendMarketCloseReminderNotification();
        } catch (Exception e) {
            log.error("장 마감 30분 전 알림 전송 중 오류 발생", e);
        }
    }
}

