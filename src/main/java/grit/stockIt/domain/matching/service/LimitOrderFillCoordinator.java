package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

// 시세 갱신 → 체결(tx1) → 정산(tx2)
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderFillCoordinator {

    private final LimitOrderExecutionService limitOrderExecutionService;
    private final ExecutionSettlementService executionSettlementService;
    private final RedisMarketDataRepository redisMarketDataRepository;

    // 처리 결과. 실패해도 예외를 던지지 않으므로 호출자가 구분할 수단이 필요하다.
    // processed 는 체결 트랜잭션이 커밋됐는지만 뜻한다 — 정산 실패는 여기 드러나지 않고
    // 미정산으로 남아 복구 배치가 집는다.
    public record FillOutcome(boolean processed, int filledOrders) {

        static FillOutcome failed() {
            return new FillOutcome(false, 0);
        }
    }

    // 큐 워커 경로의 결과. duplicate 면 이미 반영한 위치라 아무것도 하지 않았다.
    public record QueuedFillOutcome(boolean duplicate, List<FilledExecution> executions) {

        static QueuedFillOutcome alreadyApplied() {
            return new QueuedFillOutcome(true, List.of());
        }

        public int filledOrders() {
            return executions.size();
        }
    }

    public FillOutcome processFill(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());

        List<Long> executionIds;
        try {
            executionIds = limitOrderExecutionService.fill(stockCode, event);
        } catch (Exception e) {
            log.error("체결 실패. stockCode={} eventId={}", stockCode, event.eventId(), e);
            return FillOutcome.failed();
        }

        settleAll(executionIds);
        return new FillOutcome(true, executionIds.size());
    }

    // 큐 워커 경로. 체결 실패를 삼키지 않고 던진다 — 워커가 같은 위치를 다시 시도해야 유실이 없다.
    // 정산은 하지 않는다. 워커가 체결 결과를 정산 큐로 넘긴다. 현재가는 큐에 넣을 때 이미 갱신했다.
    public QueuedFillOutcome processQueuedFill(String stockCode, LimitOrderFillEvent event, CommandPosition position) {
        Optional<List<FilledExecution>> executions = limitOrderExecutionService.fillOnce(stockCode, event, position);
        if (executions.isEmpty()) {
            log.info("이미 반영한 명령이라 건너뜁니다. eventId={} position={}", event.eventId(), position);
            return QueuedFillOutcome.alreadyApplied();
        }
        return new QueuedFillOutcome(false, executions.get());
    }

    private void settleAll(List<Long> executionIds) {
        for (Long executionId : executionIds) {
            try {
                executionSettlementService.settle(executionId);
            } catch (Exception e) {
                // 체결은 이미 커밋됐다. 되돌리지 않고 복구 배치에 맡긴다.
                log.error("정산 실패. 미정산으로 남는다. executionId={}", executionId, e);
            }
        }
    }
}
