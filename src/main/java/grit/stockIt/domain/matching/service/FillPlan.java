package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;

import java.util.List;

// LimitOrderMatchPlanner.plan 의 결과물. 우선순위 정렬과 수량 배분 결과를 순수 데이터로 담는다.
//
// allocations 의 순서는 매칭 우선순위 순서이며, 호출자는 이 순서로 계좌 행 락을 잡는다.
// 즉 이 순서가 곧 데드락 프로파일이고 반환되는 Execution 순서다. 재정렬하면 안 된다.
//
// unallocatedQuantity 는 받아줄 후보가 없어 배분하지 못하고 남은 이벤트 수량이다.
public record FillPlan(
        List<FillAllocation> allocations,
        int unallocatedQuantity
) {

    // 주문장 항목 하나에 배정된 체결 수량. 인덱스가 아니라 요소 자체를 들고 있어
    // 호출자가 별도로 키 정합성을 맞출 필요가 없다. fillQuantity 는 항상 0보다 크다.
    public record FillAllocation(OrderBookEntry entry, int fillQuantity) {
    }
}
