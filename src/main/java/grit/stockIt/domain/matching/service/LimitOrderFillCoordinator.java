package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.settlement.service.ExecutionSettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

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
        try {
            executionIds = limitOrderExecutionService.fill(stockCode, event);
        } catch (Exception e) {
            log.error("체결 실패. stockCode={} eventId={}", stockCode, event.eventId(), e);
            return FillOutcome.failed();
        }

        for (Long executionId : executionIds) {
            try {
                executionSettlementService.settle(executionId);
            } catch (Exception e) {
                // 체결은 이미 커밋됐다. 되돌리지 않고 복구 배치에 맡긴다.
                log.error("정산 실패. 미정산으로 남는다. executionId={}", executionId, e);
            }
        }

        return new FillOutcome(true, executionIds.size());
    }
}
