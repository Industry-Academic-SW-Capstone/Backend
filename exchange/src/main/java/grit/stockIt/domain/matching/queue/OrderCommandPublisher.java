package grit.stockIt.domain.matching.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 거래소가 스스로 주문을 끝내는 명령(만료)을 체결과 같은 종목 파티션에 넣는다. 워커가 도착 순서대로 처리하므로
// 오더북을 바꾸는 쪽이 하나가 된다. 증권사의 주문·취소는 OrderCommandOutbox 로 들어온다.
@Component
@RequiredArgsConstructor
public class OrderCommandPublisher {

    private final MatchingCommandSender sender;

    public boolean expire(String stockCode, Long orderId) {
        return sender.send(MatchingCommand.expire(stockCode, orderId, System.currentTimeMillis())).isPresent();
    }
}
