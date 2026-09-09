package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;

/**
 * 종목별 체결 이벤트 큐 포트.
 *
 * <p>KIS 실시간 체결 피드가 유입되는 입구이며, 종목마다 독립적인 FIFO다.
 * 매칭은 종목별로 직렬 처리되므로 한 종목의 큐를 두 스레드가 동시에 소비할 일은 없다
 * ({@code MatchingLock}이 보장).
 *
 * <p>구현 선택은 {@code matching.queue.backend} 프로퍼티(redis|jpa)로 한다.
 * 오더북 백엔드와 독립적으로 지정할 수 있어, Redis 구성요소를 하나씩 얹으며
 * 각각의 기여를 분리해 측정할 수 있다.
 */
public interface MatchingEventQueue {

    /** 큐 뒤쪽에 이벤트를 넣는다(일반 유입). */
    void enqueue(String stockCode, LimitOrderFillEvent event);

    /** 큐 앞쪽에서 이벤트를 하나 꺼낸다. 비어 있으면 {@code null}. */
    LimitOrderFillEvent dequeue(String stockCode);

    /**
     * 큐 <b>앞쪽</b>에 이벤트를 되돌린다.
     *
     * <p>현금 부족 등으로 배분하지 못한 잔여 수량을 다음 차례에 즉시 재처리하기 위한 것이라,
     * 일반 유입보다 반드시 먼저 소비되어야 한다.
     */
    void requeueFront(String stockCode, LimitOrderFillEvent event);

    /** 큐에 쌓인 이벤트 수. 부하 측정에서 적체를 관측하는 용도. */
    long size(String stockCode);
}
