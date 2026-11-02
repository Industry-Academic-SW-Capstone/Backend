package grit.stockIt.global.websocket.manager;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// 웹소켓 구독 관리자
@Slf4j
@Component
public class WebSocketSubscriptionManager {
    
    // 종목코드 -> 실시간 화면 구독자 수
    private final Map<String, AtomicInteger> viewerCounts = new ConcurrentHashMap<>();
    
    // 종목코드 -> 오더북에 열려 있는 주문 번호. 거래소 통보로 넣고 뺀다 — 같은 통보가 두 번 와도 결과가 같다.
    private final Map<String, Set<Long>> openOrders = new ConcurrentHashMap<>();
    
    // 세션ID -> 구독 중인 종목 코드들
    private final Map<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    
    // subscriptionId -> 종목코드 매핑 (구독 해제 시 필요)
    private final Map<String, String> subscriptionIdToStockCode = new ConcurrentHashMap<>();
    
    // 구독자 수 증가
    public int incrementSubscribers(String stockCode) {
        int count = viewerCounts
                .computeIfAbsent(stockCode, k -> new AtomicInteger(0))
                .incrementAndGet();
        
        log.debug("종목 {} 구독자 수: {}", stockCode, count);
        return count;
    }
    
    // 구독자 수 감소
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
    
    // 현재 구독자 수 조회
    public int getSubscriberCount(String stockCode) {
        AtomicInteger count = viewerCounts.get(stockCode);
        return count != null ? count.get() : 0;
    }
    
    // 열린 주문을 더한다. 이미 있으면 false. compute 안에서 바꿔 빈 집합 정리와 겹쳐도 더한 것이 사라지지 않는다.
    public boolean addOpenOrder(String stockCode, Long orderId) {
        boolean[] added = new boolean[1];
        openOrders.compute(stockCode, (key, orders) -> {
            Set<Long> current = orders != null ? orders : ConcurrentHashMap.newKeySet();
            added[0] = current.add(orderId);
            return current;
        });
        log.debug("종목 {} 열린 주문 {} 추가={} 현재 {}건", stockCode, orderId, added[0], getOrderReferenceCount(stockCode));
        return added[0];
    }

    // 닫힌 주문을 뺀다. 없던 주문이면 false. 비면 종목을 지운다.
    public boolean removeOpenOrder(String stockCode, Long orderId) {
        boolean[] removed = new boolean[1];
        openOrders.computeIfPresent(stockCode, (key, orders) -> {
            removed[0] = orders.remove(orderId);
            return orders.isEmpty() ? null : orders;
        });
        log.debug("종목 {} 열린 주문 {} 제거={} 현재 {}건", stockCode, orderId, removed[0], getOrderReferenceCount(stockCode));
        return removed[0];
    }

    public int getOrderReferenceCount(String stockCode) {
        Set<Long> orders = openOrders.get(stockCode);
        return orders != null ? orders.size() : 0;
    }

    // 현재 화면 구독자나 주문 참조가 하나라도 존재하는지
    public boolean hasActiveReason(String stockCode) {
        return getSubscriberCount(stockCode) > 0 || getOrderReferenceCount(stockCode) > 0;
    }
    
    // 세션의 구독 종목 추가
    public boolean addSessionSubscription(String sessionId, String stockCode) {
        Set<String> stocks = sessionSubscriptions
                .computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        boolean isNewSubscription = stocks.add(stockCode);
        
        if (!isNewSubscription) {
            log.debug("세션 {}가 종목 {}을 이미 구독 중 (중복 구독 무시)", sessionId, stockCode);
        }
        
        return isNewSubscription;
    }
    
    // 세션의 구독 종목 조회
    public Set<String> getSessionSubscriptions(String sessionId) {
        return sessionSubscriptions.getOrDefault(sessionId, Set.of());
    }
    
    // 세션의 구독 종목 제거
    public void removeSessionSubscription(String sessionId, String stockCode) {
        sessionSubscriptions.computeIfPresent(sessionId, (key, stocks) -> {
            stocks.remove(stockCode);
            log.debug("세션 {}에서 종목 {} 구독 제거", sessionId, stockCode);
            // Set이 비어있으면 null 반환하여 Map에서 제거, 아니면 Set 반환
            return stocks.isEmpty() ? null : stocks;
        });
    }
    
    // 세션 제거 (연결 해제 시)
    public void removeSession(String sessionId) {
        sessionSubscriptions.remove(sessionId);
        log.debug("세션 {} 제거", sessionId);
    }
    
    // 현재 구독 중인 모든 종목 코드
    public Set<String> getAllSubscribedStocks() {
        Set<String> allStocks = ConcurrentHashMap.newKeySet();
        allStocks.addAll(viewerCounts.keySet());
        allStocks.addAll(openOrders.keySet());
        return allStocks;
    }
    
    // subscriptionId → stockCode 매핑 저장
    public void addSubscriptionMapping(String subscriptionId, String stockCode) {
        subscriptionIdToStockCode.put(subscriptionId, stockCode);
        log.debug("구독 매핑 저장: {} → {}", subscriptionId, stockCode);
    }
    
    // subscriptionId로 stockCode 조회 및 매핑 제거
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
        }
    }
}

