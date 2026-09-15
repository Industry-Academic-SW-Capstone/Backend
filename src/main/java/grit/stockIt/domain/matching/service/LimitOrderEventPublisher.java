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

// 체결 이벤트의 입구. KIS 실시간 피드와 부하 스크립트가 같은 메서드를 탄다.
//
// 시세를 갱신하고 곧바로 매칭을 호출한다. 이벤트를 보관하는 큐가 없어 수신 스레드가 체결이
// 끝날 때까지 붙들려 있고, 처리 속도를 넘는 유입이 들어오면 수신 경로 자체가 막힌다.
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
    // 락 대기가 상한을 넘기거나 DB 커넥션을 받지 못하면 이벤트를 처리하지 못한 채 끝난다.
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
