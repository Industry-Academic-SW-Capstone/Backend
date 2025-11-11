package grit.stockIt.global.websocket.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 웹소켓 구독 관리자
 * 종목별 구독자 수를 추적하고 관리
 */
@Slf4j
@Component
public class WebSocketSubscriptionManager {
    
    // 종목코드 -> 실시간 화면 구독자 수
    private final Map<String, AtomicInteger> viewerCounts = new ConcurrentHashMap<>();
    
    // 종목코드 -> 실시간 매칭을 위해 유지해야 하는 주문 참조 수
    private final Map<String, AtomicInteger> orderReferenceCounts = new ConcurrentHashMap<>();
    
    // 세션ID -> 구독 중인 종목 코드들
    private final Map<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    
    // subscriptionId -> 종목코드 매핑 (구독 해제 시 필요)
    private final Map<String, String> subscriptionIdToStockCode = new ConcurrentHashMap<>();
    
    /**
     * 구독자 수 증가
     * @return 증가 후 구독자 수
     */
    public int incrementSubscribers(String stockCode) {
        int count = viewerCounts
                .computeIfAbsent(stockCode, k -> new AtomicInteger(0))
                .incrementAndGet();
        
        log.debug("종목 {} 구독자 수: {}", stockCode, count);
        return count;
    }
    
    /**
     * 구독자 수 감소
     * @return 감소 후 구독자 수
     */
    public int decrementSubscribers(String stockCode) {
        AtomicInteger count = viewerCounts.get(stockCode);
        if (count == null) {
            return 0;
        }
        
        int newCount = Math.max(count.decrementAndGet(), 0);
        if (newCount <= 0) {
            viewerCounts.remove(stockCode);
        }
        cleanupIfInactive(stockCode);
        
        log.debug("종목 {} 구독자 수: {}", stockCode, newCount);
        return newCount;
    }
    
    /**
     * 현재 구독자 수 조회
     */
    public int getSubscriberCount(String stockCode) {
        AtomicInteger count = viewerCounts.get(stockCode);
        return count != null ? count.get() : 0;
    }
    
    /**
     * 주문 기준 구독 참조 수 증가
     */
    public int incrementOrderReference(String stockCode) {
        int count = orderReferenceCounts
                .computeIfAbsent(stockCode, k -> new AtomicInteger(0))
                .incrementAndGet();
        
        log.debug("종목 {} 주문 참조 수 증가: {}", stockCode, count);
        return count;
    }
    
    /**
     * 주문 기준 구독 참조 수 감소
     */
    public int decrementOrderReference(String stockCode) {
        AtomicInteger count = orderReferenceCounts.get(stockCode);
        if (count == null) {
            return 0;
        }
        
        int newCount = Math.max(count.decrementAndGet(), 0);
        if (newCount <= 0) {
            orderReferenceCounts.remove(stockCode);
        }
        cleanupIfInactive(stockCode);
        
        log.debug("종목 {} 주문 참조 수 감소: {}", stockCode, newCount);
        return newCount;
    }
    
    public int getOrderReferenceCount(String stockCode) {
        AtomicInteger count = orderReferenceCounts.get(stockCode);
        return count != null ? count.get() : 0;
    }
    
    /**
     * 현재 화면 구독자나 주문 참조가 하나라도 존재하는지
     */
    public boolean hasActiveReason(String stockCode) {
        return getSubscriberCount(stockCode) > 0 || getOrderReferenceCount(stockCode) > 0;
    }
    
    /**
     * 세션의 구독 종목 추가
     * @return 새로 추가된 경우 true, 이미 구독 중이면 false
     */
    public boolean addSessionSubscription(String sessionId, String stockCode) {
        Set<String> stocks = sessionSubscriptions
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        boolean isNewSubscription = stocks.add(stockCode);
        
        if (!isNewSubscription) {
            log.debug("세션 {}가 종목 {}을 이미 구독 중 (중복 구독 무시)", sessionId, stockCode);
        }
        
        return isNewSubscription;
    }
    
    /**
     * 세션의 구독 종목 조회
     */
    public Set<String> getSessionSubscriptions(String sessionId) {
        return sessionSubscriptions.getOrDefault(sessionId, Set.of());
    }
    
    /**
     * 세션 제거 (연결 해제 시)
     */
    public void removeSession(String sessionId) {
        sessionSubscriptions.remove(sessionId);
        log.debug("세션 {} 제거", sessionId);
    }
    
    /**
     * 현재 구독 중인 모든 종목 코드
     */
    public Set<String> getAllSubscribedStocks() {
        return viewerCounts.keySet();
    }
    
    /**
     * subscriptionId → stockCode 매핑 저장
     */
    public void addSubscriptionMapping(String subscriptionId, String stockCode) {
        subscriptionIdToStockCode.put(subscriptionId, stockCode);
        log.debug("구독 매핑 저장: {} → {}", subscriptionId, stockCode);
    }
    
    /**
     * subscriptionId로 stockCode 조회 및 매핑 제거
     * @return 종목 코드 (없으면 null)
     */
    public String removeSubscriptionMapping(String subscriptionId) {
        String stockCode = subscriptionIdToStockCode.remove(subscriptionId);
        if (stockCode != null) {
            log.debug("구독 매핑 제거: {} → {}", subscriptionId, stockCode);
        }
        return stockCode;
    }
    
    private void cleanupIfInactive(String stockCode) {
        if (!hasActiveReason(stockCode)) {
            viewerCounts.remove(stockCode);
            orderReferenceCounts.remove(stockCode);
        }
    }
}

