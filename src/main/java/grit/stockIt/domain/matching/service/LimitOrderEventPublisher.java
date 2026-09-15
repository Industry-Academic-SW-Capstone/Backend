package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderEventPublisher {

    private final LimitOrderMatchingService limitOrderMatchingService;
    private final RedisMarketDataRepository redisMarketDataRepository;

    // 처리 결과. 실패해도 예외를 던지지 않으므로 호출자가 구분할 수단이 필요하다.
    public record PublishResult(boolean processed, int filledOrders) {

        static PublishResult failed() {
            return new PublishResult(false, 0);
        }
    }

    @EventListener
    public void handleLimitOrderFill(LimitOrderFillEventMessage message) {
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                message.eventId(),
                message.orderMethod(),
                message.price(),
                message.quantity(),
                message.eventTimestamp()
        );
        publish(message.stockCode(), event);
    }

    // 시세 갱신 → 종목 락 → 체결.
    public PublishResult publish(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());

        try {
            List<Execution> executions = limitOrderMatchingService.match(stockCode, event);
            return new PublishResult(true, executions.size());
        } catch (Exception e) {
            log.error("체결 이벤트 처리 실패. stockCode={} eventId={}", stockCode, event.eventId(), e);
            return PublishResult.failed();
        }
    }
}
