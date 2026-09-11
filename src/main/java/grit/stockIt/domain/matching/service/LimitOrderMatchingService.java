package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.lock.MatchingLock;
import grit.stockIt.domain.matching.queue.MatchingEventQueue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 종목별 체결 이벤트를 하나씩 꺼내 매칭 정산으로 넘긴다.
 *
 * <p>락과 큐 모두 포트에 위임한다({@link MatchingLock}, {@link MatchingEventQueue}).
 * 두 백엔드를 {@code matching.orderbook.backend}·{@code matching.queue.backend}로
 * 독립 지정할 수 있어, Redis 구성요소를 하나씩 얹으며 각각의 기여를 분리해 측정할 수 있다.
 *
 * <p><b>락 획득에 실패하면 이벤트는 큐에 남고 빈 리스트가 반환된다.</b> 호출자는 200을 받으므로
 * 처리량만 보면 적체를 놓친다. 큐 길이({@link MatchingEventQueue#size})를 함께 관측해야 한다.
 *
 * <p>남은 이벤트는 {@code MatchingEventDispatcher}가 {@link #drainQueue}로 비운다.
 * 발행 시점에만 소비하던 구조에서는 유입이 멈추면 큐에 남은 이벤트가 영구히 방치됐다.
 */
@Slf4j
@Service
public class LimitOrderMatchingService {

    private final LimitOrderExecutionService limitOrderExecutionService;
    private final MatchingLock matchingLock;
    private final MatchingEventQueue matchingEventQueue;

    /** 체결된 주문 수 누적. 요청 응답이 아니라 앱 지표로 센다 — 소비가 워커로 옮겨졌기 때문이다. */
    private final Counter executionCounter;

    /**
     * 이벤트 도착부터 체결 완료까지. <b>거래소 관점의 핵심 지표다.</b>
     *
     * <p>HTTP 응답 지연은 큐에 넣는 시간일 뿐이라 적체가 생겨도 오르지 않는다.
     * 밀림은 이 값과 큐 길이에서 드러난다.
     */
    private final Timer eventLatency;

    public LimitOrderMatchingService(
            LimitOrderExecutionService limitOrderExecutionService,
            MatchingLock matchingLock,
            MatchingEventQueue matchingEventQueue,
            MeterRegistry meterRegistry) {
        this.limitOrderExecutionService = limitOrderExecutionService;
        this.matchingLock = matchingLock;
        this.matchingEventQueue = matchingEventQueue;
        this.executionCounter = Counter.builder("matching.executions")
                .description("체결된 주문 수")
                .register(meterRegistry);
        this.eventLatency = Timer.builder("matching.event.latency")
                .description("체결 이벤트 도착부터 체결 완료까지")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * 큐가 빌 때까지 이벤트를 소비한다. 워커({@code MatchingEventDispatcher})가 호출한다.
     *
     * <p>종료 이유를 구분해 돌려준다. <b>락 경합으로 멈춘 것과 큐가 비어 멈춘 것은 후속 조치가
     * 다르다</b> — 전자는 재시도해야 하고 후자는 끝이다. 구분하지 않으면 워커가 빈 큐를 두고
     * 영원히 재시도한다.
     *
     * <p><b>이벤트 1건마다 락을 다시 잡는다.</b> 락을 쥔 채 전부 처리하면 R0에서는
     * {@code AdvisoryMatchingLock}이 {@code @Transactional}이라 드레인 전체가 한 트랜잭션이 되어
     * 커밋이 배치되는데, R3는 락이 트랜잭션 밖이라 그렇지 않다. 백엔드마다 커밋 횟수가 달라지면
     * 저장소 성능 비교가 오염되므로 건당 커밋을 유지한다.
     */
    public DrainResult drainQueue(String stockCode) {
        int consumed = 0;
        while (true) {
            Step step = consumeOne(stockCode);
            if (step != Step.CONSUMED) {
                return new DrainResult(consumed, step == Step.LOCK_BUSY);
            }
            consumed++;
        }
    }

    /**
     * 드레인 결과.
     *
     * @param consumed      이번 호출에서 소비한 이벤트 수
     * @param lockContended 락을 잡지 못해 멈췄는지. true면 다른 주체가 처리 중이므로 재시도가 필요하다
     */
    public record DrainResult(int consumed, boolean lockContended) {
    }

    private enum Step {
        CONSUMED,
        QUEUE_EMPTY,
        LOCK_BUSY
    }

    private void recordMetrics(LimitOrderFillEvent event, List<Execution> executions) {
        executionCounter.increment(executions.size());
        Long arrivedAt = event.eventTimestamp();
        if (arrivedAt != null) {
            long elapsed = System.currentTimeMillis() - arrivedAt;
            if (elapsed >= 0) {
                eventLatency.record(Duration.ofMillis(elapsed));
            }
        }
    }

    private Step consumeOne(String stockCode) {
        Optional<Boolean> outcome = matchingLock.tryRun(stockCode, () -> {
            LimitOrderFillEvent event = matchingEventQueue.dequeue(stockCode);
            if (event == null) {
                return Boolean.FALSE;
            }
            List<Execution> executions = limitOrderExecutionService.distributeEvent(stockCode, event);
            recordMetrics(event, executions);
            return Boolean.TRUE;
        });
        if (outcome.isEmpty()) {
            return Step.LOCK_BUSY;
        }
        return Boolean.TRUE.equals(outcome.get()) ? Step.CONSUMED : Step.QUEUE_EMPTY;
    }

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
