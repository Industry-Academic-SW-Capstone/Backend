package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 지정가 오더북 저장소 포트.
 *
 * <p>Redis(ZSet)와 RDB(trade_order 인덱스) 두 구현을 같은 계약 아래 두어,
 * 매칭 정산 로직을 고정한 채 오더북 자료구조만 교체해 성능을 비교하기 위한 인터페이스다.
 * 구현 선택은 {@code matching.orderbook.backend} 프로퍼티(redis|jpa)로 한다.
 *
 * <p>구현체는 종목별 직렬화(가격 우선·시간 우선)를 스스로 보장하지 않는다.
 * 직렬화는 {@code MatchingLock}이 담당한다.
 */
public interface OrderBookStore {

    /** 오더북에 주문을 등록한다. 활성 상태(PENDING/PARTIALLY_FILLED)가 아니면 무시한다. */
    void addOrder(Order order);

    /** 오더북에서 주문을 제거한다. */
    void removeOrder(Long orderId, String stockCode, OrderMethod orderMethod);

    /** 주문의 잔여 수량을 갱신한다. 잔여가 0 이하이면 제거와 동일하게 처리한다. */
    void updateRemainingQuantity(Long orderId, String stockCode, OrderMethod orderMethod, int remainingQuantity);

    /**
     * 체결 이벤트에 대응할 반대 방향 주문 후보를 가격 우선 순으로 최대 {@code maxOrders}건 조회한다.
     *
     * @param takerMethod 체결 이벤트의 방향. BUY면 SELL 주문을, SELL이면 BUY 주문을 후보로 찾는다.
     * @param priceLimit  체결 가격. SELL 후보는 이 가격 이하, BUY 후보는 이 가격 이상만 대상이다.
     */
    List<OrderBookEntry> fetchMatchingEntries(String stockCode, OrderMethod takerMethod, BigDecimal priceLimit, int maxOrders);

    /** 주문이 오더북에 존재하는지 확인한다. */
    boolean exists(Long orderId, String stockCode, OrderMethod orderMethod);

    /**
     * 오더북에 있는 모든 주문 ID를 "종목코드:주문방향" 키로 반환한다.
     * 유령 주문(오더북에는 있으나 DB에서는 이미 체결된 주문) 정리 배치가 사용한다.
     */
    Map<String, Set<Long>> getAllOrderIdsByStock();
}
