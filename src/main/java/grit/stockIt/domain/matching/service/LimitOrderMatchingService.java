package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.lock.StockMatchingLock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LimitOrderMatchingService {

    private final LimitOrderExecutionService limitOrderExecutionService;
    private final StockMatchingLock stockMatchingLock;

    // 락을 잡기까지 기다린 시간. 유입이 종목당 상한을 넘으면 이 값이 오른다.
    private final Timer lockWaitTimer;

    // 락을 잡은 뒤 정산이 끝나기까지. 곧 임계 구역의 길이이고, 종목당 상한은 이 값의 역수다.
    // 처리량에서 역산하지 않고 직접 재야 무엇을 줄였을 때 상한이 움직이는지 확인할 수 있다.
    private final Timer settlementTimer;

    public LimitOrderMatchingService(
            LimitOrderExecutionService limitOrderExecutionService,
            StockMatchingLock stockMatchingLock,
            MeterRegistry meterRegistry) {
        this.limitOrderExecutionService = limitOrderExecutionService;
        this.stockMatchingLock = stockMatchingLock;
        this.lockWaitTimer = Timer.builder("matching.lock.wait")
                .description("종목 락을 잡기까지 기다린 시간")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);
        this.settlementTimer = Timer.builder("matching.settlement")
                .description("락을 잡은 뒤 정산이 끝나기까지 — 임계 구역 길이")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);
    }

    // 종목 락을 잡고 체결 이벤트를 정산한다. 여기서 연 트랜잭션에 distributeEvent(REQUIRED)가
    // 참여하므로, 락은 정산 전 구간에서 유지되다 커밋 시 풀린다.
    // 락 대기가 matching.lock.timeout 을 넘기면 예외가 난다.
    //
    // 커밋은 이 메서드가 반환된 뒤 트랜잭션 프록시가 수행하므로 settlement 에 포함되지 않는다.
    // 요청 전체 시간에서 두 타이머를 빼면 커밋과 프록시 몫이 남는다.
    @Transactional
    public List<Execution> match(String stockCode, LimitOrderFillEvent event) {
        long lockStart = System.nanoTime();
        try {
            stockMatchingLock.acquire(stockCode);
        } finally {
            lockWaitTimer.record(System.nanoTime() - lockStart, TimeUnit.NANOSECONDS);
        }

        long settlementStart = System.nanoTime();
        try {
            return limitOrderExecutionService.distributeEvent(stockCode, event);
        } finally {
            settlementTimer.record(System.nanoTime() - settlementStart, TimeUnit.NANOSECONDS);
        }
    }
}
