package grit.stockIt.domain.matching.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 주문을 끝내는 명령(취소·만료)은 체결과 같은 종목 파티션에 넣는다. 워커가 셋을 도착 순서대로 처리하므로
// 오더북을 바꾸는 쪽이 하나가 된다.
@Component
@RequiredArgsConstructor
public class OrderCommandPublisher {

    private final MatchingCommandSender sender;

    public boolean cancel(String stockCode, Long orderId) {
        return sender.send(MatchingCommand.cancel(stockCode, orderId, System.currentTimeMillis())).isPresent();
    }

    public boolean expire(String stockCode, Long orderId) {
        return sender.send(MatchingCommand.expire(stockCode, orderId, System.currentTimeMillis())).isPresent();
    }
}
