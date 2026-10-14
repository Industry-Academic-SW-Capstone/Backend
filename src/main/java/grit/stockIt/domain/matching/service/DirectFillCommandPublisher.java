package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
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
