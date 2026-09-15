package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;

import java.util.List;

// LimitOrderMatchPlanner.plan 의 결과물. 우선순위 정렬과 수량 배분 결과를 순수 데이터로 담는다.
//
public record FillPlan(
        List<FillAllocation> allocations,
        int unallocatedQuantity
) {

    // 주문장 항목 하나에 배정된 체결 수량. 인덱스가 아니라 요소 자체를 들고 있어
    // 호출자가 별도로 키 정합성을 맞출 필요가 없다. fillQuantity 는 항상 0보다 크다.
    public record FillAllocation(OrderBookEntry entry, int fillQuantity) {
    }
}
