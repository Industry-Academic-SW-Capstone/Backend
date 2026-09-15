package grit.stockIt.domain.order.service;

import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.util.TransactionHandler;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 주문이 걸린 종목의 실시간 시세 구독을 켜고 끈다.
//
// 오더북 등록은 하지 않는다. 주문 행(trade_order) 자체가 오더북이라 저장한 순간 올라간 것이다.
//
// 구독은 참조 카운트로 관리되는 자원이고 KIS WebSocket 구독이라는 외부 부수효과를 동반한다.
// 커밋 전에 켰다가 롤백되면 카운트를 되돌릴 주체가 없어, 주문이 없는 종목의 구독이 영영 열린 채
// 남는다. 그래서 커밋 후에만 켠다.
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSubscriptionService {

    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;

    // 계약: 호출자의 @Transactional 활성 구간 안에서 동기 호출해야 한다. 활성 트랜잭션이 없으면
    // afterCommit 의 else 분기가 즉시 실행되어, 저장되지 않은 주문 때문에 구독이 열린다.
    public void subscribeAfterCommit(Order order, Stock stock) {
        TransactionHandler.afterCommit(() -> {
            try {
                orderSubscriptionCoordinator.registerLimitOrder(stock.getCode());
            } catch (Exception e) {
                log.error("주문 생성 후 시세 구독 등록 실패. orderId={} stockCode={}",
                        order.getOrderId(), stock.getCode(), e);
            }
        });
    }

    // 주문 취소 시 구독을 해제한다. 주문 행의 상태가 바뀌는 순간 오더북에서는 이미 빠진다.
    public void unsubscribeOnCancel(Order order) {
        orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
    }

    // 시장가 주문 저장 전 구독 선등록(체결 이벤트 수신을 위해 저장 전에 구독 시작).
    public void preSubscribe(String stockCode) {
        orderSubscriptionCoordinator.registerLimitOrder(stockCode);
    }
}
