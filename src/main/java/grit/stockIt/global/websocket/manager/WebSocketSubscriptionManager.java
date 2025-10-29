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
    
    // 종목코드 -> 구독자 수
    private final Map<String, AtomicInteger> subscriberCounts = new ConcurrentHashMap<>();
    
    // 세션ID -> 구독 중인 종목 코드들
    private final Map<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    
    /**
     * 구독자 수 증가
     * @return 증가 후 구독자 수
     */
    public int incrementSubscribers(String stockCode) {
        int count = subscriberCounts
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
        AtomicInteger count = subscriberCounts.get(stockCode);
        if (count == null) {
            return 0;
        }
        
        int newCount = count.decrementAndGet();
        
        // 구독자가 0이 되면 제거
        if (newCount <= 0) {
            subscriberCounts.remove(stockCode);
        }
        
        log.debug("종목 {} 구독자 수: {}", stockCode, newCount);
        return Math.max(newCount, 0);
    }
    
    /**
     * 현재 구독자 수 조회
     */
    public int getSubscriberCount(String stockCode) {
        AtomicInteger count = subscriberCounts.get(stockCode);
        return count != null ? count.get() : 0;
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
        return subscriberCounts.keySet();
    }
}

