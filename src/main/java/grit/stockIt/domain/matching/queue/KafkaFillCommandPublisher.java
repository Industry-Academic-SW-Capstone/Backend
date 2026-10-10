package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaFillCommandPublisher implements FillCommandPublisher {

    private final MatchingCommandSender sender;
    private final MatchingQueueMetrics metrics;
    private final RedisMarketDataRepository redisMarketDataRepository;

    // 현재가는 체결 처리와 무관한 시세라 틱이 도착한 지금 갱신한다. 워커에서 하면 큐가 밀린 만큼 시세도 늦게 보이고,
    // 종목을 한 줄로 처리하는 워커의 왕복이 하나 는다.
    @Override
    public FillDispatchResult publish(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());
        FillDispatchResult result = sender.send(MatchingCommand.fill(stockCode, event, System.currentTimeMillis()))
                .<FillDispatchResult>map(metadata -> new FillDispatchResult.Queued(metadata.partition(), metadata.offset()))
                .orElseGet(FillDispatchResult.Failed::new);
        metrics.enqueued(result instanceof FillDispatchResult.Queued);
        return result;
    }
}
