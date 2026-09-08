package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.OrderMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LimitOrderMatchPlanner}의 순수 단위 테스트. Mockito/Spring 없이 {@code new}로만
 * 인스턴스화한다.
 */
@DisplayName("LimitOrderMatchPlanner 단위 테스트")
class LimitOrderMatchPlannerTest {

    private final LimitOrderMatchPlanner planner = new LimitOrderMatchPlanner();

    private static OrderBookEntry entry(long orderId, BigDecimal price, int remaining, long createdAt) {
        return new OrderBookEntry(orderId, "005930", OrderMethod.BUY, price, remaining, remaining, createdAt, 1L);
    }

    private static OrderBookEntry entry(long orderId, OrderMethod method, BigDecimal price, int remaining, long createdAt) {
        return new OrderBookEntry(orderId, "005930", method, price, remaining, remaining, createdAt, 1L);
    }

    @Test
    @DisplayName("BUY 테이커는 SELL 후보를 가격 오름차순(저렴한 순)으로 정렬한다")
    void plan_BuyTaker_SortsSellCandidatesAscending() {
        OrderBookEntry high = entry(1L, OrderMethod.SELL, new BigDecimal("105"), 10, 1000);
        OrderBookEntry low = entry(2L, OrderMethod.SELL, new BigDecimal("100"), 10, 2000);
        OrderBookEntry mid = entry(3L, OrderMethod.SELL, new BigDecimal("102"), 10, 3000);

        FillPlan plan = planner.plan(List.of(high, low, mid), OrderMethod.BUY, 30);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId())
                .containsExactly(2L, 3L, 1L); // 100, 102, 105 오름차순
    }

    @Test
    @DisplayName("SELL 테이커는 BUY 후보를 가격 내림차순(비싼 순)으로 정렬한다")
    void plan_SellTaker_SortsBuyCandidatesDescending() {
        OrderBookEntry low = entry(1L, OrderMethod.BUY, new BigDecimal("95"), 10, 1000);
        OrderBookEntry high = entry(2L, OrderMethod.BUY, new BigDecimal("100"), 10, 2000);
        OrderBookEntry mid = entry(3L, OrderMethod.BUY, new BigDecimal("98"), 10, 3000);

        FillPlan plan = planner.plan(List.of(low, high, mid), OrderMethod.SELL, 30);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId())
                .containsExactly(2L, 3L, 1L); // 100, 98, 95 내림차순
    }

    @Test
    @DisplayName("가격이 같으면 createdAtEpochMillis 오름차순으로 타이브레이크한다 (orderId와 반대 방향인 안티코릴레이트 픽스처)")
    void plan_EqualPrice_TiebreaksByCreatedAtAscending_AntiCorrelatedFixture() {
        // orderId와 createdAt을 의도적으로 반대 방향으로 배치: orderId 내림차순이면서 createdAt 오름차순.
        // 만약 뮤테이션이 정렬 키를 createdAt에서 orderId로 바꿔치기해도 이 픽스처가 그 변이를 탐지한다.
        OrderBookEntry entryA = entry(10L, OrderMethod.SELL, new BigDecimal("100"), 10, 100L); // orderId 큼, createdAt 작음 → 먼저
        OrderBookEntry entryB = entry(5L, OrderMethod.SELL, new BigDecimal("100"), 10, 200L);  // orderId 작음, createdAt 큼 → 나중

        FillPlan plan = planner.plan(List.of(entryB, entryA), OrderMethod.BUY, 20);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId())
                .containsExactly(10L, 5L); // createdAt 100 < 200 이므로 orderId=10이 먼저
    }

    @Test
    @DisplayName("가격과 타임스탬프가 모두 같으면 입력 순서를 유지한다 (현재 동작을 있는 그대로 기록)")
    void plan_EqualPriceAndTimestamp_RecordsCurrentStableOrder() {
        OrderBookEntry first = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 500L);
        OrderBookEntry second = entry(2L, OrderMethod.SELL, new BigDecimal("100"), 10, 500L);

        FillPlan plan = planner.plan(List.of(first, second), OrderMethod.BUY, 20);

        // Comparator가 완전히 동률일 때 Stream.sorted()는 안정 정렬이므로 입력 순서가 보존된다.
        assertThat(plan.allocations()).extracting(a -> a.entry().orderId())
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("시장가 매수 센티널 가격(999999999)은 별도 분기 없이 가격 축만으로 최우선 정렬된다 (AC-8)")
    void plan_MarketBuySentinelPrice_SortsFirstByPriceAxisAlone() {
        OrderBookEntry marketBuy = entry(1L, OrderMethod.BUY, new BigDecimal("999999999"), 10, 1000);
        OrderBookEntry limitBuy = entry(2L, OrderMethod.BUY, new BigDecimal("100"), 10, 500); // 더 이른 시각이어도 가격이 우선

        // SELL 테이커 → BUY 후보 가격 내림차순 → 999999999가 100보다 커서 먼저 나와야 함
        FillPlan plan = planner.plan(List.of(limitBuy, marketBuy), OrderMethod.SELL, 20);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId())
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("시장가 매도 센티널 가격(0.01)은 별도 분기 없이 가격 축만으로 최우선 정렬된다 (AC-8)")
    void plan_MarketSellSentinelPrice_SortsFirstByPriceAxisAlone() {
        OrderBookEntry marketSell = entry(1L, OrderMethod.SELL, new BigDecimal("0.01"), 10, 1000);
        OrderBookEntry limitSell = entry(2L, OrderMethod.SELL, new BigDecimal("100"), 10, 500);

        // BUY 테이커 → SELL 후보 가격 오름차순 → 0.01이 100보다 작아서 먼저 나와야 함
        FillPlan plan = planner.plan(List.of(limitSell, marketSell), OrderMethod.BUY, 20);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId())
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("소진된(isExhausted) 엔트리는 할당에서 건너뛰지만 exhaustedEntries에는 나타난다")
    void plan_ExhaustedEntry_SkippedFromAllocationButAppearsInExhaustedEntries() {
        OrderBookEntry exhausted = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 0, 1000); // remaining=0
        OrderBookEntry active = entry(2L, OrderMethod.SELL, new BigDecimal("101"), 10, 2000);

        FillPlan plan = planner.plan(List.of(exhausted, active), OrderMethod.BUY, 20);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId()).containsExactly(2L);
        assertThat(plan.exhaustedEntries()).extracting(OrderBookEntry::orderId).containsExactly(1L);
    }

    @Test
    @DisplayName("할당 루프의 break 이후에 위치한 소진 엔트리도 exhaustedEntries에 포함된다")
    void plan_ExhaustedEntryAfterAllocationBreak_StillAppearsInExhaustedEntries() {
        // BUY 테이커 → SELL 후보 오름차순: entry(100,active,remain=10) 먼저 전량 배정되어 break,
        // 그 다음 정렬 위치(101, exhausted)는 할당 루프에 도달하지 않지만 exhaustedEntries에는 있어야 함
        OrderBookEntry active = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 1000);
        OrderBookEntry exhaustedAfterBreak = entry(2L, OrderMethod.SELL, new BigDecimal("101"), 0, 2000);

        FillPlan plan = planner.plan(List.of(active, exhaustedAfterBreak), OrderMethod.BUY, 10); // 이벤트 수량 10 = active로 완전히 소진

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId()).containsExactly(1L);
        assertThat(plan.unallocatedQuantity()).isZero();
        assertThat(plan.exhaustedEntries()).extracting(OrderBookEntry::orderId).containsExactly(2L);
    }

    @Test
    @DisplayName("이벤트 수량이 첫 엔트리의 잔여 수량보다 적으면 단일 부분 배정이 발생한다")
    void plan_EventQuantityLessThanFirstEntryRemaining_SinglePartialAllocation() {
        OrderBookEntry entry = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 1000);

        FillPlan plan = planner.plan(List.of(entry), OrderMethod.BUY, 4);

        assertThat(plan.allocations()).hasSize(1);
        assertThat(plan.allocations().get(0).fillQuantity()).isEqualTo(4);
        assertThat(plan.unallocatedQuantity()).isZero();
    }

    @Test
    @DisplayName("이벤트 수량이 모든 후보 잔여 수량의 합과 같으면 unallocatedQuantity는 0이다")
    void plan_EventQuantityEqualsTotalRemaining_UnallocatedIsZero() {
        OrderBookEntry a = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 1000);
        OrderBookEntry b = entry(2L, OrderMethod.SELL, new BigDecimal("101"), 5, 2000);

        FillPlan plan = planner.plan(List.of(a, b), OrderMethod.BUY, 15);

        assertThat(plan.unallocatedQuantity()).isZero();
        assertThat(plan.allocations()).hasSize(2);
    }

    @Test
    @DisplayName("이벤트 수량이 모든 후보 잔여 수량의 합보다 크면 unallocatedQuantity는 0보다 크다")
    void plan_EventQuantityGreaterThanTotalRemaining_UnallocatedIsPositive() {
        OrderBookEntry a = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 1000);

        FillPlan plan = planner.plan(List.of(a), OrderMethod.BUY, 15);

        assertThat(plan.unallocatedQuantity()).isEqualTo(5);
        assertThat(plan.allocations()).hasSize(1);
        assertThat(plan.allocations().get(0).fillQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("이벤트 수량이 1인 경계값에서도 정상 배정된다")
    void plan_EventQuantityOfOne_BoundaryAllocatesOne() {
        OrderBookEntry entry = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 1000);

        FillPlan plan = planner.plan(List.of(entry), OrderMethod.BUY, 1);

        assertThat(plan.allocations()).hasSize(1);
        assertThat(plan.allocations().get(0).fillQuantity()).isEqualTo(1);
        assertThat(plan.unallocatedQuantity()).isZero();
    }

    @Test
    @DisplayName("후보 목록이 비어있으면 배정 없이 unallocatedQuantity는 이벤트 수량 그대로다")
    void plan_EmptyCandidateList_NoAllocationsUnallocatedEqualsEventQuantity() {
        FillPlan plan = planner.plan(List.of(), OrderMethod.BUY, 10);

        assertThat(plan.allocations()).isEmpty();
        assertThat(plan.exhaustedEntries()).isEmpty();
        assertThat(plan.unallocatedQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("수량이 소진되면 뒤에 남은 활성 후보가 있어도 할당 루프가 조기 종료된다")
    void plan_QuantityExhausted_TerminatesEarlyLeavingLaterActiveCandidatesUnallocated() {
        OrderBookEntry first = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 10, 1000);
        OrderBookEntry second = entry(2L, OrderMethod.SELL, new BigDecimal("101"), 10, 2000); // 활성이지만 도달 못함

        FillPlan plan = planner.plan(List.of(first, second), OrderMethod.BUY, 10);

        assertThat(plan.allocations()).extracting(a -> a.entry().orderId()).containsExactly(1L);
        assertThat(plan.unallocatedQuantity()).isZero();
        assertThat(plan.exhaustedEntries()).isEmpty(); // second는 소진 상태가 아니므로 exhaustedEntries에도 없음
    }

    @Test
    @DisplayName("Math.min 경계: 잔여 수량과 이벤트 수량이 정확히 같으면 전량 배정되고 다음 엔트리로 넘어간다")
    void plan_MathMinOffByOneBoundary_ExactMatchAllocatesFullyAndContinues() {
        OrderBookEntry exact = entry(1L, OrderMethod.SELL, new BigDecimal("100"), 5, 1000);
        OrderBookEntry next = entry(2L, OrderMethod.SELL, new BigDecimal("101"), 5, 2000);

        // 이벤트 수량 5 == exact.remaining 정확히 일치 → exact가 5주 모두 배정되고 remainingQuantity(이벤트)=0이 되어
        // 다음 루프 반복 시작에서 break, next는 도달하지 않음
        FillPlan plan = planner.plan(List.of(exact, next), OrderMethod.BUY, 5);

        assertThat(plan.allocations()).hasSize(1);
        assertThat(plan.allocations().get(0).entry().orderId()).isEqualTo(1L);
        assertThat(plan.allocations().get(0).fillQuantity()).isEqualTo(5);
        assertThat(plan.unallocatedQuantity()).isZero();

        // 경계값을 1 늘려서(6) next에도 1주가 배정되는지 확인 (Math.min의 다른 쪽 경계)
        FillPlan plan2 = planner.plan(List.of(exact, next), OrderMethod.BUY, 6);
        assertThat(plan2.allocations()).hasSize(2);
        assertThat(plan2.allocations().get(1).entry().orderId()).isEqualTo(2L);
        assertThat(plan2.allocations().get(1).fillQuantity()).isEqualTo(1);
        assertThat(plan2.unallocatedQuantity()).isZero();
    }
}
