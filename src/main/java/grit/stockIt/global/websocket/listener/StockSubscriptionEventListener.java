package grit.stockIt.global.websocket.listener;

import grit.stockIt.global.websocket.client.KisWebSocketClient;
import grit.stockIt.global.websocket.manager.WebSocketSubscriptionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * STOMP 구독 이벤트를 감지하여 KIS API 구독을 관리하는 리스너
 * 
 * 클라이언트가 /topic/stock/{stockCode}를 구독하면 자동으로 감지하여
 * 필요시 KIS API에 구독을 요청하고, 마지막 구독자가 해제되면 KIS 구독도 해제
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockSubscriptionEventListener {
    
    private final KisWebSocketClient kisWebSocketClient;
    private final WebSocketSubscriptionManager subscriptionManager;
    
    /**
     * 클라이언트가 /topic/stock/{stockCode}를 구독할 때 자동 호출
     */
    @EventListener
    public void handleSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        String destination = headers.getDestination();
        String sessionId = headers.getSessionId();
        String subscriptionId = headers.getSubscriptionId();
        
        if (destination == null || !destination.startsWith("/topic/stock/")) {
            return;
        }
        
        // /topic/stock/005930 → 005930 추출
        String stockCode = destination.substring("/topic/stock/".length());
        
        log.debug("세션 {}가 종목 {} 구독 요청 (subscriptionId: {})", sessionId, stockCode, subscriptionId);
        
        // subscriptionId → stockCode 매핑 저장 (구독 해제 시 필요)
        if (subscriptionId != null) {
            subscriptionManager.addSubscriptionMapping(subscriptionId, stockCode);
        }
        
        // 세션 구독 기록 (중복 체크)
        boolean isNewSubscription = subscriptionManager.addSessionSubscription(sessionId, stockCode);
        
        // 새로운 구독인 경우에만 처리
        if (isNewSubscription) {
            boolean alreadyActive = subscriptionManager.hasActiveReason(stockCode);
            int count = subscriptionManager.incrementSubscribers(stockCode);
            if (!alreadyActive) {
                log.info("종목 {} 실시간 구독 시작 (viewer 기반). 현재 viewerCount={}, orderCount={}",
                        stockCode, count, subscriptionManager.getOrderReferenceCount(stockCode));
                subscribeToKis(stockCode);
            }
        }
    }
    
    /**
     * 클라이언트가 구독을 해제할 때 자동 호출
     */
    @EventListener
    public void handleUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headers.getSessionId();
        String subscriptionId = headers.getSubscriptionId();
        
        log.debug("세션 {} 구독 해제 (subscriptionId: {})", sessionId, subscriptionId);
        
        if (subscriptionId == null) {
            log.warn("subscriptionId가 null입니다");
            return;
        }
        
        // subscriptionId로 종목 코드 조회 및 매핑 제거
        String stockCode = subscriptionManager.removeSubscriptionMapping(subscriptionId);
        
        if (stockCode == null) {
            log.debug("subscriptionId {}에 해당하는 종목 코드를 찾을 수 없습니다", subscriptionId);
            return;
        }
        
        log.info("종목 {} 구독 해제 (세션: {})", stockCode, sessionId);
        
        // 구독자 수 감소
        subscriptionManager.decrementSubscribers(stockCode);
        
        if (!subscriptionManager.hasActiveReason(stockCode)) {
            log.info("종목 {} 실시간 구독 해제 (viewer 기준 해제 후 참조 없음)", stockCode);
            unsubscribeFromKis(stockCode);
        }
    }
    
    /**
     * 클라이언트 연결이 끊어질 때 자동 호출
     */
    @EventListener
    public void handleDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();
        log.debug("세션 {} 연결 해제", sessionId);
        
        // 해당 세션이 구독하던 종목들 처리
        var subscribedStocks = subscriptionManager.getSessionSubscriptions(sessionId);
        
        subscribedStocks.forEach(stockCode -> {
            // 구독자 수 감소
            subscriptionManager.decrementSubscribers(stockCode);
            
            if (!subscriptionManager.hasActiveReason(stockCode)) {
                log.info("종목 {} 실시간 구독 해제 (세션 종료 후 참조 없음)", stockCode);
                unsubscribeFromKis(stockCode);
            }
        });
        
        // 세션 제거
        subscriptionManager.removeSession(sessionId);
    }
    
    /**
     * KIS 구독
     */
    private void subscribeToKis(String stockCode) {
        kisWebSocketClient.subscribe(stockCode);
    }
    
    /**
     * KIS 구독 해제
     */
    private void unsubscribeFromKis(String stockCode) {
        kisWebSocketClient.unsubscribe(stockCode);
    }
}

