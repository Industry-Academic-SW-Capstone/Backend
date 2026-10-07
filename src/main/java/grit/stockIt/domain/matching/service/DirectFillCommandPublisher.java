package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.queue.enabled", havingValue = "false", matchIfMissing = true)
public class DirectFillCommandPublisher implements FillCommandPublisher {

    private final LimitOrderFillCoordinator limitOrderFillCoordinator;

    @Override
    public FillDispatchResult publish(String stockCode, LimitOrderFillEvent event) {
        LimitOrderFillCoordinator.FillOutcome outcome = limitOrderFillCoordinator.processFill(stockCode, event);
        return outcome.processed()
                ? new FillDispatchResult.Processed(outcome.filledOrders())
                : new FillDispatchResult.Failed();
    }
}
