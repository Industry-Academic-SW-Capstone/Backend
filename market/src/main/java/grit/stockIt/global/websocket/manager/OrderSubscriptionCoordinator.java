package grit.stockIt.global.websocket.manager;

import grit.stockIt.global.websocket.client.KisWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 오더북에 열린 주문이 있는 종목은 보는 사람이 없어도 실시간 구독을 유지한다 — 체결 틱이 와야 체결된다.
// 열린 주문은 거래소 통보(exchange.reports)로만 알고, 주문 번호로 넣고 빼서 같은 통보가 두 번 와도 결과가 같다.
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSubscriptionCoordinator {

    private final WebSocketSubscriptionManager subscriptionManager;
    private final KisWebSocketClient kisWebSocketClient;

    // 거래소가 주문을 오더북에 넣었다(ACCEPTED). 기동 시 복구도 이 길로 넣는다.
    public void orderOpened(String stockCode, Long orderId) {
        boolean alreadyActive = subscriptionManager.hasActiveReason(stockCode);
        if (!subscriptionManager.addOpenOrder(stockCode, orderId)) {
            return;
        }
        log.debug("종목 {} 열린 주문 등록. orderId={} orderCount={}, viewerCount={}", stockCode, orderId,
                subscriptionManager.getOrderReferenceCount(stockCode), subscriptionManager.getSubscriberCount(stockCode));

        if (!alreadyActive) {
            log.info("종목 {} 실시간 구독 시작 (주문 등록으로 인한 활성화)", stockCode);
            kisWebSocketClient.subscribe(stockCode);
        }
    }

    // 주문이 오더북에서 빠졌다(전부 체결·취소·만료·거부).
    public void orderClosed(String stockCode, Long orderId) {
        if (!subscriptionManager.removeOpenOrder(stockCode, orderId)) {
            return;
        }
        log.debug("종목 {} 열린 주문 해제. orderId={} orderCount={}, viewerCount={}", stockCode, orderId,
                subscriptionManager.getOrderReferenceCount(stockCode), subscriptionManager.getSubscriberCount(stockCode));

        if (!subscriptionManager.hasActiveReason(stockCode)) {
            log.info("종목 {} 실시간 구독 해제 (주문 참조 및 뷰어 모두 없음)", stockCode);
            kisWebSocketClient.unsubscribe(stockCode);
        }
    }
}
