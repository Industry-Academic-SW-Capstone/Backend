package grit.stockIt.global.support;

import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.order.entity.Order;

// 테스트가 증권사 주문(trade_order)을 직접 저장할 때 거래소 오더북에도 올린다 — 운영에서 ORDER 명령을 받은 거래소가 하는 일이다.
// 체결 수량은 저장된 주문의 것을 그대로 옮긴다.
public final class ExchangeBook {

    private ExchangeBook() {
    }

    public static ExchangeOrder place(ExchangeOrderRepository repository, Order order) {
        ExchangeOrder exchangeOrder = ExchangeOrder.accept(order.getOrderId(), order.getAccount().getAccountId(),
                order.getStock().getCode(), order.getOrderMethod(), order.getOrderType(), order.getPrice(),
                order.getQuantity());
        if (order.getFilledQuantity() > 0) {
            exchangeOrder.applyFill(order.getFilledQuantity());
        }
        return repository.save(exchangeOrder);
    }
}
