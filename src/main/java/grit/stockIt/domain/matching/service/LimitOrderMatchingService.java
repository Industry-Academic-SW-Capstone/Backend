package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.lock.MatchingLock;
import grit.stockIt.domain.matching.queue.MatchingEventQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 종목별 체결 이벤트를 하나씩 꺼내 매칭 정산으로 넘긴다.
 *
 * <p>락과 큐 모두 포트에 위임한다({@link MatchingLock}, {@link MatchingEventQueue}).
 * 두 백엔드를 {@code matching.orderbook.backend}·{@code matching.queue.backend}로
 * 독립 지정할 수 있어, Redis 구성요소를 하나씩 얹으며 각각의 기여를 분리해 측정할 수 있다.
 *
 * <p><b>측정 시 주의:</b> 락 획득에 실패하면 이벤트는 큐에 남고 빈 리스트가 반환된다.
 * 큐를 소비하는 경로는 {@link LimitOrderEventPublisher}의 발행 시점 하나뿐이고 폴링 워커가
 * 없으므로, 같은 종목의 다음 이벤트가 도착할 때까지 처리가 지연된다. 처리량만 보면 적체를
 * 놓치므로 큐 길이({@link MatchingEventQueue#size})를 함께 관측해야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderMatchingService {

    private final LimitOrderExecutionService limitOrderExecutionService;
    private final MatchingLock matchingLock;
    private final MatchingEventQueue matchingEventQueue;

    // 락 획득 후 이벤트 1건을 꺼내 정산에 넘긴다. 락 획득 실패 시 이벤트는 큐에 남는다.
    public List<Execution> consumeNextEvent(String stockCode) {
        return matchingLock.tryRun(stockCode, () -> {
            LimitOrderFillEvent event = matchingEventQueue.dequeue(stockCode);
            if (event == null) {
                return List.<Execution>of();
            }
            // 내부 서비스 호출 (프록시를 통해 @Transactional 동작)
            return limitOrderExecutionService.distributeEvent(stockCode, event);
        }).orElseGet(List::of);
    }
}
