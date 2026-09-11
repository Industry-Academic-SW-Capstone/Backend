package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.service.LimitOrderMatchingService.DrainResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 큐에 남은 체결 이벤트를 종목별로 비우는 워커.
 *
 * <p><b>왜 필요한가.</b> 이전에는 큐를 소비하는 경로가 {@link LimitOrderEventPublisher}의 발행
 * 시점 하나뿐이었다. 요청 1건이 이벤트 1건을 넣고 1건을 꺼내려 시도하되, 종목 락을 못 잡으면
 * 기다리지 않고 포기했다. 그 결과 유입이 처리를 초과하면 큐가 단조 증가하고,
 * <b>유입이 멈추면 남은 이벤트가 영구히 방치됐다.</b> 부하 측정에서 유입 120/s 중 46/s만
 * 체결되고 3,786건이 큐에 남은 채 끝났다.
 *
 * <p>지연·에러율로는 전혀 드러나지 않는다는 점이 특히 나빴다. 락 실패가 빠르게 200을
 * 반환하므로 p99가 오히려 낮아진다.
 *
 * <p><b>구조.</b> 요청은 큐에 넣기만 하고, 비우는 일은 워커가 맡는다.
 *
 * <pre>
 *   요청     enqueue → (인라인 1건 시도) → 응답
 *   워커     락 획득 → 큐가 빌 때까지 소비 → 해제
 * </pre>
 *
 * <p><b>방치 방지.</b> 드레인 요청이 들어오면 {@code pending}에 표시하고 작업을 제출한다.
 * 작업은 표시를 먼저 지운 뒤 처리하므로, 처리 중에 들어온 요청은 새 작업을 만든다.
 * 락을 못 잡으면 표시를 되돌려 스위퍼가 다시 시도한다. 이 조합으로
 * "마지막 dequeue 직후 커밋된 이벤트"가 누락되는 경쟁 구간을 덮는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEventDispatcher {

    private final LimitOrderMatchingService limitOrderMatchingService;

    /** 드레인이 필요한 종목. 워커 수보다 종목이 많을 수 있으므로 집합으로 둔다. */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /**
     * 워커 수. 종목 간에는 병렬이지만 워커마다 DB 커넥션을 1개 쓰므로
     * HikariCP 풀 크기(기본 10)를 넘기면 커넥션 경합이 매칭 경합으로 오인된다.
     */
    /**
     * 워커 사용 여부. 끄면 요청 인라인 소비만 남는다.
     *
     * <p>테스트에서는 끈다. 큐 잔여를 동기적으로 단언하는 특성화 테스트들이
     * 비동기 드레인과 경쟁하기 때문이다. 워커 자체는
     * {@code MatchingEventDispatcherTest}가 직접 기동해 검증한다.
     */
    @Value("${matching.worker.enabled:true}")
    private boolean enabled;

    @Value("${matching.worker.threads:8}")
    private int workerThreads;

    /** 락을 못 잡아 되돌려진 종목을 다시 시도하는 주기. */
    @Value("${matching.worker.sweep-interval-ms:100}")
    private long sweepIntervalMillis;

    private ExecutorService workers;
    private ScheduledExecutorService sweeper;
    private volatile boolean running;

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("매칭 워커 비활성 (matching.worker.enabled=false)");
            return;
        }
        AtomicInteger seq = new AtomicInteger();
        workers = Executors.newFixedThreadPool(workerThreads, runnable -> {
            Thread thread = new Thread(runnable, "matching-worker-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "matching-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        running = true;
        sweeper.scheduleWithFixedDelay(
                this::sweep, sweepIntervalMillis, sweepIntervalMillis, TimeUnit.MILLISECONDS);
        log.info("매칭 워커 기동: threads={} sweepInterval={}ms", workerThreads, sweepIntervalMillis);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    /**
     * 해당 종목의 큐를 비우도록 요청한다. 호출자를 막지 않는다.
     *
     * <p>이미 대기 중인 종목이면 작업을 새로 만들지 않는다 — 기존 작업이 큐를 끝까지 비운다.
     */
    public void requestDrain(String stockCode) {
        if (!running || stockCode == null) {
            return;
        }
        if (pending.add(stockCode)) {
            submit(stockCode);
        }
    }

    private void submit(String stockCode) {
        try {
            workers.execute(() -> drain(stockCode));
        } catch (RejectedExecutionException e) {
            // 종료 중이거나 큐가 가득 찬 경우. 표시를 남겨 스위퍼가 재시도한다.
            pending.add(stockCode);
        }
    }

    private void drain(String stockCode) {
        // 처리 시작 전에 표시를 지운다. 처리 중 도착한 이벤트는 새 작업을 만들 수 있어야 한다.
        pending.remove(stockCode);
        try {
            DrainResult result = limitOrderMatchingService.drainQueue(stockCode);
            if (result.lockContended()) {
                // 다른 주체가 같은 종목을 처리 중이다. 스위퍼가 다시 시도한다.
                // 큐가 비어 끝난 경우에는 표시하지 않는다 — 안 그러면 영원히 재시도한다.
                pending.add(stockCode);
            }
        } catch (Exception e) {
            log.error("큐 드레인 실패. stockCode={}", stockCode, e);
            pending.add(stockCode);
        }
    }

    /** 락 경합으로 되돌려진 종목을 주기적으로 다시 시도한다. */
    private void sweep() {
        if (!running) {
            return;
        }
        for (String stockCode : pending) {
            submit(stockCode);
        }
    }
}
