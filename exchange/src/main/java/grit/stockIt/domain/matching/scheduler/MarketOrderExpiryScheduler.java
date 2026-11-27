package grit.stockIt.domain.matching.scheduler;

import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.entity.ExchangeOrderStatus;
import grit.stockIt.domain.matching.queue.OrderCommandPublisher;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.global.shard.ShardContext;
import grit.stockIt.global.shard.ShardRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 시장가 주문의 당일 만료. 오더북을 가진 거래소가 대상을 찾아 만료 명령만 넣고, 처리는 그 종목의 체결 워커가 한다.
// 넣지 못한 주문은 다음 매매일 실행 때 다시 대상이 된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketOrderExpiryScheduler {

    private final ExchangeOrderRepository exchangeOrderRepository;
    private final OrderCommandPublisher orderCommandPublisher;
    private final ShardRouter shardRouter;

    // 장 마감(15:30) 이후. 마감 직전 체결이 정리될 여유를 두고 15:40 에 돈다.
    @Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Seoul")
    public void expireMarketOrders() {
        List<ExchangeOrder> targets = findTargetsOnEveryShard();
        if (targets.isEmpty()) {
            return;
        }

        int enqueued = 0;
        for (ExchangeOrder target : targets) {
            if (orderCommandPublisher.expire(target.getStockCode(), target.getOrderId())) {
                enqueued++;
            } else {
                log.error("시장가 주문 만료 명령을 넣지 못했습니다. orderId={} stockCode={}",
                        target.getOrderId(), target.getStockCode());
            }
        }
        log.info("시장가 주문 당일 만료 명령: 대상={} 접수={}", targets.size(), enqueued);
    }

    private List<ExchangeOrder> findTargetsOnEveryShard() {
        List<ExchangeOrder> targets = new ArrayList<>();
        for (int shard = 0; shard < shardRouter.shardCount(); shard++) {
            targets.addAll(ShardContext.call(shard,
                    () -> exchangeOrderRepository.findByTypeAndStatus(OrderType.MARKET, ExchangeOrderStatus.OPEN)));
        }
        return targets;
    }
}
