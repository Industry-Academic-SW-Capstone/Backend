package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 지정가 주문장 후보로부터 체결 계획을 산출하는 순수 로직
public class LimitOrderMatchPlanner {
    public FillPlan plan(List<OrderBookEntry> candidates, OrderMethod takerMethod, int eventQuantity) {
        List<OrderBookEntry> orderedEntries = sortByPriority(candidates, takerMethod);

        int remainingQuantity = eventQuantity;
        List<FillPlan.FillAllocation> allocations = new ArrayList<>();

        for (OrderBookEntry entry : orderedEntries) {
            if (remainingQuantity <= 0) {
                break;
            }

            if (entry.isExhausted()) {
                // 잔여가 없는 후보는 배분 대상이 아니다. 조회 조건에서 걸러지지만 방어적으로 둔다.
                continue;
            }

            int fillQuantity = Math.min(entry.remainingQuantity(), remainingQuantity);
            if (fillQuantity <= 0) {
                continue;
            }

            allocations.add(new FillPlan.FillAllocation(entry, fillQuantity));
            remainingQuantity -= fillQuantity;
        }

        return new FillPlan(allocations, remainingQuantity);
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
