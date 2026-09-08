package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 지정가 주문장 후보 목록으로부터 체결 계획({@link FillPlan})을 산출하는 순수 로직.
 * Redis/DB 등 외부 협력자가 없으며 상태를 갖지 않는다(무-인자 생성자).
 *
 * <h2>계약</h2>
 * <ol>
 *     <li>{@link FillPlan#exhaustedEntries()}는 정렬된 전체 후보 목록에서
 *     {@link OrderBookEntry#isExhausted()}가 참인 모든 항목을 정렬 순서 그대로 담는다.
 *     수량 할당 루프가 이벤트 수량 소진으로 중간에 {@code break}하더라도, break 이후에
 *     위치한 소진 항목 역시 반드시 포함된다. 이는 별도의 순회 단계에서 계산하기 때문이며,
 *     production의 Redis 삭제 로직이 정렬된 전체 목록을 무조건(할당 루프의 break와 무관하게)
 *     순회하는 것과 동일한 시맨틱을 보존하기 위함이다.</li>
 *     <li>(AC-8) 이 클래스의 정렬 비교자는 시장가 주문 우선순위를 별도로 구현하지 않는다.
 *     시장가 우선순위는 {@code Order.MARKET_BUY_SENTINEL_PRICE}(999999999)와
 *     {@code Order.MARKET_SELL_SENTINEL_PRICE}(0.01)가 가격 축에 이미 인코딩되어 있어
 *     가격 비교만으로 자연히 실현된다. 비교자에 시장가 분기를 추가하지 말 것.</li>
 * </ol>
 */
public class LimitOrderMatchPlanner {

    /**
     * 후보 목록을 우선순위에 따라 정렬하고, 이벤트 수량 한도 내에서 체결을 배정한다.
     *
     * @param candidates    매칭 후보 {@link OrderBookEntry} 목록 (정렬되지 않은 원본)
     * @param takerMethod   체결 이벤트의 매수/매도 구분 (상대 후보의 정렬 방향을 결정)
     * @param eventQuantity 이번 체결 이벤트에서 배정 가능한 총 수량
     * @return 배정 결과, 소진된 항목 목록, 미배정 잔여 수량을 담은 {@link FillPlan}
     */
    public FillPlan plan(List<OrderBookEntry> candidates, OrderMethod takerMethod, int eventQuantity) {
        List<OrderBookEntry> orderedEntries = sortByPriority(candidates, takerMethod);

        int remainingQuantity = eventQuantity;
        List<FillPlan.FillAllocation> allocations = new ArrayList<>();

        for (OrderBookEntry entry : orderedEntries) {
            if (remainingQuantity <= 0) {
                break;
            }

            if (entry.isExhausted()) {
                // 소진된 주문은 나중에 DB 업데이트 후 Redis에서 삭제
                continue;
            }

            int fillQuantity = Math.min(entry.remainingQuantity(), remainingQuantity);
            if (fillQuantity <= 0) {
                continue;
            }

            allocations.add(new FillPlan.FillAllocation(entry, fillQuantity));
            remainingQuantity -= fillQuantity;
        }

        List<OrderBookEntry> exhaustedEntries = new ArrayList<>();
        for (OrderBookEntry entry : orderedEntries) {
            if (entry.isExhausted()) {
                exhaustedEntries.add(entry);
            }
        }

        return new FillPlan(allocations, exhaustedEntries, remainingQuantity);
    }

    private List<OrderBookEntry> sortByPriority(List<OrderBookEntry> entries, OrderMethod takerMethod) {
        Comparator<OrderBookEntry> comparator = Comparator.comparing(OrderBookEntry::price);
        if (takerMethod == OrderMethod.SELL) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparingLong(OrderBookEntry::createdAtEpochMillis);
        return entries.stream().sorted(comparator).toList();
    }
}
