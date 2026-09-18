package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

// 시세 갱신 → 체결(tx1) → 정산(tx2)
@Slf4j
@Service
public class LimitOrderFillCoordinator {

    private final LimitOrderExecutionService limitOrderExecutionService;
    private final ExecutionSettlementService executionSettlementService;
    private final RedisMarketDataRepository redisMarketDataRepository;

    // 락 대기와 체결과 커밋을 모두 포함한다. 곧 종목 락을 쥐고 있던 시간이고,
    // 지금까지 뺄셈으로 추정하던 커밋 몫을 이 타이머로 직접 잴 수 있다.
    private final Timer fillTotalTimer;

    // 정산 트랜잭션 전체. 락 밖이라 종목당 상한과 무관하다.
    private final Timer settleTimer;

    public LimitOrderFillCoordinator(
            LimitOrderExecutionService limitOrderExecutionService,
            ExecutionSettlementService executionSettlementService,
            RedisMarketDataRepository redisMarketDataRepository,
            MeterRegistry meterRegistry) {
        this.limitOrderExecutionService = limitOrderExecutionService;
        this.executionSettlementService = executionSettlementService;
        this.redisMarketDataRepository = redisMarketDataRepository;
        this.fillTotalTimer = Timer.builder("matching.fill.total")
                .description("락 대기부터 체결 커밋까지 — 종목 락 보유 시간")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);
        this.settleTimer = Timer.builder("matching.settle")
                .description("정산 트랜잭션 전체 — 락 밖이라 상한과 무관")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);
    }

    // 처리 결과. 실패해도 예외를 던지지 않으므로 호출자가 구분할 수단이 필요하다.
    // processed 는 체결 트랜잭션이 커밋됐는지만 뜻한다 — 정산 실패는 여기 드러나지 않고
    // 미정산으로 남아 복구 배치가 집는다.
    public record FillOutcome(boolean processed, int filledOrders) {

        static FillOutcome failed() {
            return new FillOutcome(false, 0);
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
        processFill(message.stockCode(), event);
    }

    public FillOutcome processFill(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());

        List<Long> executionIds;
        long fillStart = System.nanoTime();
        try {
            executionIds = limitOrderExecutionService.fill(stockCode, event);
        } catch (Exception e) {
            log.error("체결 실패. stockCode={} eventId={}", stockCode, event.eventId(), e);
            return FillOutcome.failed();
        } finally {
            fillTotalTimer.record(System.nanoTime() - fillStart, TimeUnit.NANOSECONDS);
        }

        long settleStart = System.nanoTime();
        try {
            for (Long executionId : executionIds) {
                try {
                    executionSettlementService.settle(executionId);
                } catch (Exception e) {
                    // 체결은 이미 커밋됐다. 되돌리지 않고 복구 배치에 맡긴다.
                    log.error("정산 실패. 미정산으로 남는다. executionId={}", executionId, e);
                }
            }
        } finally {
            settleTimer.record(System.nanoTime() - settleStart, TimeUnit.NANOSECONDS);
        }

        return new FillOutcome(true, executionIds.size());
    }
}
