package grit.stockIt.domain.order.scheduler;

import grit.stockIt.domain.matching.queue.OrderCommandPublisher;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.order.repository.ExpirableMarketOrder;
import grit.stockIt.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// 시장가 주문의 당일 만료. 여기서는 대상을 찾아 만료 명령만 넣고, 취소·홀딩 해제는 그 종목의 체결 워커가 한다.
// 넣지 못한 주문은 다음 매매일 실행 때 다시 대상이 된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketOrderExpiryScheduler {

    private static final List<OrderStatus> EXPIRABLE_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final OrderCommandPublisher orderCommandPublisher;

    // 장 마감(15:30) 이후. 마감 직전 체결이 정리될 여유를 두고 15:40 에 돈다.
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void expireMarketOrders() {
        List<ExpirableMarketOrder> targets = orderRepository.findExpirableMarketOrders(OrderType.MARKET, EXPIRABLE_STATUSES);
        if (targets.isEmpty()) {
            return;
        }

        int enqueued = 0;
        for (ExpirableMarketOrder target : targets) {
            if (orderCommandPublisher.expire(target.stockCode(), target.orderId())) {
                enqueued++;
            } else {
                log.error("시장가 주문 만료 명령을 넣지 못했습니다. orderId={} stockCode={}",
                        target.orderId(), target.stockCode());
            }
        }
        log.info("시장가 주문 당일 만료 명령: 대상={} 접수={}", targets.size(), enqueued);
    }
}
