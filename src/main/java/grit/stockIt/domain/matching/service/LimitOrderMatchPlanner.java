package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 지정가 주문장 후보로부터 체결 계획을 산출하는 순수 로직. 협력자 0개, 상태 없음.
//
// 계약 1: exhaustedEntries 는 정렬된 전체 후보에서 isExhausted 가 참인 항목을 정렬 순서대로
// 모두 담는다. 수량 할당 루프가 이벤트 수량 소진으로 break 해도 그 이후의 소진 항목까지
// 포함된다. 별도 순회로 계산하며, production 의 Redis 삭제 로직이 할당 루프의 break 와
// 무관하게 정렬 전체를 순회하는 시맨틱을 보존하기 위함이다.
//
// 계약 2: 정렬 비교자에 시장가 분기를 추가하지 말 것. 시장가 우선순위는
// Order.MARKET_BUY_SENTINEL_PRICE(999999999)와 MARKET_SELL_SENTINEL_PRICE(0.01)가
// 가격 축에 이미 인코딩되어 있어 가격 비교만으로 실현된다.
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

    // Sort contract co-owned by: RedisOrderBookRepository.fetchMatchingEntries (ZSET direction/truncation), Order sentinel prices (MARKET_BUY/SELL_SENTINEL_PRICE)
    private List<OrderBookEntry> sortByPriority(List<OrderBookEntry> entries, OrderMethod takerMethod) {
        Comparator<OrderBookEntry> comparator = Comparator.comparing(OrderBookEntry::price);
        if (takerMethod == OrderMethod.SELL) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparingLong(OrderBookEntry::createdAtEpochMillis);
        return entries.stream().sorted(comparator).toList();
    }
}
