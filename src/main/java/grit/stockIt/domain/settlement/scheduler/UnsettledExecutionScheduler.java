package grit.stockIt.domain.settlement.scheduler;

import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

// 체결은 커밋됐는데 정산이 남은 건을 주워 마저 처리한다.
// 정산 실패는 되돌리지 않고 미정산으로 남기므로, 이 배치가 없으면 영영 반영되지 않는다.
@Slf4j
@Component
public class UnsettledExecutionScheduler {

    private final ExecutionSettlementService executionSettlementService;

    // 스크레이프마다 anti-join 을 돌리면 execution 전체를 훑어 측정을 오염시킨다.
    // 배치가 돌 때 갱신한 값을 게이지가 읽는다 — 스케줄링이 꺼진 환경에서는 갱신되지 않는다.
    private final AtomicLong unsettledCount = new AtomicLong();

    // 체결 직후 정산이 돌고 있는 건을 배치가 가로채면 유니크 제약에 걸려 한쪽이 헛돈다.
    @Value("${settlement.recovery.grace-period:30s}")
    private Duration gracePeriod;

    @Value("${settlement.recovery.batch-size:100}")
    private int batchSize;

    public UnsettledExecutionScheduler(ExecutionSettlementService executionSettlementService,
                                       MeterRegistry meterRegistry) {
        this.executionSettlementService = executionSettlementService;
        Gauge.builder("settlement.unsettled", unsettledCount, AtomicLong::doubleValue)
                .description("체결됐지만 아직 정산되지 않은 건수 — 복구 배치가 돌 때 갱신된다")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${settlement.recovery.interval:60s}")
    public void recoverUnsettledExecutions() {
        LocalDateTime cutoff = LocalDateTime.now().minus(gracePeriod);

        List<Long> executionIds = executionSettlementService.findUnsettledExecutionIds(cutoff, batchSize);
        if (executionIds.isEmpty()) {
            unsettledCount.set(0);
            return;
        }

        int settled = 0;
        for (Long executionId : executionIds) {
            try {
                executionSettlementService.settle(executionId);
                settled++;
            } catch (Exception e) {
                log.error("미정산 체결 복구 실패. executionId={}", executionId, e);
            }
        }

        unsettledCount.set(executionSettlementService.countUnsettled(cutoff));
        log.info("미정산 체결 복구: 대상={} 정산={} 남음={}", executionIds.size(), settled, unsettledCount.get());
    }
}
