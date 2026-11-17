package grit.stockIt.domain.order.service;

import grit.stockIt.domain.matching.queue.MatchingCommand;
import grit.stockIt.domain.matching.queue.MatchingTopics;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.global.outbox.OutboxRecord;
import grit.stockIt.global.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 증권사가 거래소로 보내는 주문·취소 명령을 그 트랜잭션 안에서 아웃박스에 적는다. 종목 코드가 키다.
// 주문과 취소가 같은 아웃박스를 지나므로 적힌 순서대로 도착한다 — 취소가 주문보다 먼저 거래소에 닿지 않는다.
@Component
@RequiredArgsConstructor
public class OrderCommandOutbox {

    private final OutboxWriter outboxWriter;

    public void order(Order order) {
        append(MatchingCommand.order(order.getStock().getCode(), order.getOrderId(), order.getAccount().getAccountId(),
                order.getOrderMethod(), order.getOrderType(), order.getPrice(), order.getQuantity(),
                System.currentTimeMillis()));
    }

    public void cancel(String stockCode, Long orderId) {
        append(MatchingCommand.cancel(stockCode, orderId, System.currentTimeMillis()));
    }

    private void append(MatchingCommand command) {
        outboxWriter.append(List.of(new OutboxRecord(MatchingTopics.COMMANDS, command.stockCode(), command)));
    }
}
