package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;

import java.util.List;

/**
 * {@link LimitOrderMatchPlanner#plan(List, grit.stockIt.domain.order.entity.OrderMethod, int)}의
 * 결과물. 우선순위 정렬 및 수량 할당 결과를 순수 데이터로 표현한다.
 *
 * @param allocations         체결이 배정된 항목들. 각 {@link FillAllocation}은 배정 대상
 *                             {@link OrderBookEntry}를 인덱스가 아닌 요소 자체로 보유하여
 *                             호출자가 별도 키 정합성을 유지할 필요가 없다.
 * @param exhaustedEntries     정렬된 전체 후보 목록에서 {@link OrderBookEntry#isExhausted()}가
 *                             참인 모든 항목. 할당 루프가 수량 소진으로 {@code break}된 이후에
 *                             위치한 소진 항목도 반드시 포함된다(정렬 순서 유지) —
 *                             production 삭제 로직이 정렬된 전체 목록을 무조건 순회하기 때문이다.
 * @param unallocatedQuantity  후보 소진 등으로 인해 이번 계획에서 배정하지 못하고 남은 이벤트 수량.
 */
public record FillPlan(
        List<FillAllocation> allocations,
        List<OrderBookEntry> exhaustedEntries,
        int unallocatedQuantity
) {

    /**
     * 하나의 {@link OrderBookEntry}에 배정된 체결 수량.
     *
     * @param entry        배정 대상 주문장 항목(요소 자체를 보유, 인덱스 아님)
     * @param fillQuantity 이 항목에 배정된 체결 수량 (항상 0보다 큼)
     */
    public record FillAllocation(OrderBookEntry entry, int fillQuantity) {
    }
}
