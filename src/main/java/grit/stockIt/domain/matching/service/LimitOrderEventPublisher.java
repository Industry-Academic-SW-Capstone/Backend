package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import grit.stockIt.domain.matching.queue.MatchingEventQueue;
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

    private final MatchingEventQueue matchingEventQueue;
    private final LimitOrderMatchingService limitOrderMatchingService;
    private final RedisMarketDataRepository redisMarketDataRepository;

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

    /**
     * 시세 갱신 → 큐 적재 → 매칭 소비. 체결 이벤트가 들어왔을 때의 표준 경로다.
     *
     * <p>KIS 피드는 {@link #handleLimitOrderFill}을 통해 들어오고, 부하 테스트는
     * 이 메서드를 직접 호출한다. 두 경로가 같은 코드를 타야 측정이 실제를 반영한다.
     *
     * <p>적재·소비 실패는 로그만 남기고 빈 목록을 반환한다 — 체결 피드 수신이
     * 매칭 실패로 중단되면 안 되기 때문이다.
     */
    public List<Execution> publish(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());

        try {
            matchingEventQueue.enqueue(stockCode, event);
        } catch (Exception e) {
            log.error("지정가 이벤트 적재 실패. stockCode={} event={}", stockCode, event, e);
            return List.of();
        }

        try {
            return limitOrderMatchingService.consumeNextEvent(stockCode);
        } catch (Exception e) {
            log.error("지정가 이벤트 처리 실패. stockCode={} event={}", stockCode, event, e);
            return List.of();
        }
    }
}
