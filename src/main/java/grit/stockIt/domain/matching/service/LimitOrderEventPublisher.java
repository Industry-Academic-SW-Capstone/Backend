package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import grit.stockIt.domain.matching.queue.MatchingEventQueue;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderEventPublisher {

    private final MatchingEventQueue matchingEventQueue;
    private final LimitOrderMatchingService limitOrderMatchingService;
    private final MatchingEventDispatcher matchingEventDispatcher;
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
     * <p><b>큐에 넣기만 하고 즉시 반환한다.</b> 소비는 {@link MatchingEventDispatcher}가
     * 맡는다. 실제 입력은 KIS 실시간 피드이므로, 수신 경로가 체결 처리를 기다리면
     * 시세 수신 자체가 밀린다. 적재 실패는 로그만 남긴다.
     *
     * <p>체결 건수와 체결 지연은 응답이 아니라 앱 지표({@code matching.executions},
     * {@code matching.event.latency})로 관측한다.
     */
    public void publish(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());

        try {
            matchingEventQueue.enqueue(stockCode, event);
        } catch (Exception e) {
            log.error("지정가 이벤트 적재 실패. stockCode={} event={}", stockCode, event, e);
            return;
        }

        // 소비는 워커가 전담한다. 큐를 둔 구조에서 수신 경로가 체결까지 기다리면
        // 큐의 의미가 없어지고, 실제 입력인 KIS 피드 수신이 매칭 때문에 막힌다.
        matchingEventDispatcher.requestDrain(stockCode);
    }
}
