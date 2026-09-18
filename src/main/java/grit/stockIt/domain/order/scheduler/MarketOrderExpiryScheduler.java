package grit.stockIt.domain.order.scheduler;

import grit.stockIt.domain.order.service.MarketOrderExpiryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketOrderExpiryScheduler {

    private final MarketOrderExpiryService marketOrderExpiryService;

    // 장 마감(15:30) 이후. 마감 직전 체결이 정리될 여유를 두고 15:40 에 돈다.
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void expireMarketOrders() {
        List<Long> orderIds = marketOrderExpiryService.findExpirableOrderIds();
        if (orderIds.isEmpty()) {
            return;
        }

        int expired = 0;
        for (Long orderId : orderIds) {
            try {
                if (marketOrderExpiryService.expire(orderId)) {
                    expired++;
                }
            } catch (Exception e) {
                log.error("시장가 주문 만료 실패. orderId={}", orderId, e);
            }
        }
        log.info("시장가 주문 당일 만료 완료: 대상={} 만료={}", orderIds.size(), expired);
    }
}
