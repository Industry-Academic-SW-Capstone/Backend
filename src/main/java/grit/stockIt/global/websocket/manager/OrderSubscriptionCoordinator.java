package grit.stockIt.global.websocket.manager;

import grit.stockIt.global.websocket.client.KisWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 지정가 주문으로 인해 실시간 구독을 유지해야 하는 경우를 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSubscriptionCoordinator {

    private final WebSocketSubscriptionManager subscriptionManager;
    private final KisWebSocketClient kisWebSocketClient;

    /**
     * 지정가 주문이 새로 등록되었을 때 호출
     */
    public void registerLimitOrder(String stockCode) {
        boolean alreadyActive = subscriptionManager.hasActiveReason(stockCode);
        int orderCount = subscriptionManager.incrementOrderReference(stockCode);
        log.debug("종목 {} 주문 참조 등록. 현재 orderCount={}, viewerCount={}",
                stockCode, orderCount, subscriptionManager.getSubscriberCount(stockCode));

        if (!alreadyActive) {
            log.info("종목 {} 실시간 구독 시작 (주문 등록으로 인한 활성화)", stockCode);
            kisWebSocketClient.subscribe(stockCode);
        }
    }

    /**
     * 지정가 주문이 완료/취소되어 등록 해제될 때 호출
     */
    public void unregisterLimitOrder(String stockCode) {
        int remaining = subscriptionManager.decrementOrderReference(stockCode);
        log.debug("종목 {} 주문 참조 해제. 남은 orderCount={}, viewerCount={}",
                stockCode, remaining, subscriptionManager.getSubscriberCount(stockCode));

        if (!subscriptionManager.hasActiveReason(stockCode)) {
            log.info("종목 {} 실시간 구독 해제 (주문 참조 및 뷰어 모두 없음)", stockCode);
            kisWebSocketClient.unsubscribe(stockCode);
        }
    }
}

