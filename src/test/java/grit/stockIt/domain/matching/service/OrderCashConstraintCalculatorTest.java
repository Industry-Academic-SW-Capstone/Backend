package grit.stockIt.domain.matching.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OrderCashConstraintCalculator}의 순수 단위 테스트. Mockito/Spring 없이 {@code new}로만
 * 인스턴스화한다.
 */
@DisplayName("OrderCashConstraintCalculator 단위 테스트")
class OrderCashConstraintCalculatorTest {

    private final OrderCashConstraintCalculator calculator = new OrderCashConstraintCalculator();

    @Test
    @DisplayName("가격이 null이면 0을 반환한다")
    void calculate_NullPrice_ReturnsZero() {
        int result = calculator.calculate(new BigDecimal("1000"), null, 5);
        assertThat(result).isZero();
    }

    @Test
    @DisplayName("가격의 signum이 0 이하이면 0을 반환한다")
    void calculate_NonPositivePrice_ReturnsZero() {
        assertThat(calculator.calculate(new BigDecimal("1000"), BigDecimal.ZERO, 5)).isZero();
        assertThat(calculator.calculate(new BigDecimal("1000"), new BigDecimal("-1"), 5)).isZero();
    }

    @Test
    @DisplayName("현금이 가격보다 정확히 1 단위 부족하면 0을 반환한다")
    void calculate_CashOneUnitBelowPrice_ReturnsZero() {
        int result = calculator.calculate(new BigDecimal("99"), new BigDecimal("100"), 5);
        assertThat(result).isZero();
    }

    @Test
    @DisplayName("현금이 가격과 정확히 같으면 1을 반환한다")
    void calculate_CashEqualsPrice_ReturnsOne() {
        int result = calculator.calculate(new BigDecimal("100"), new BigDecimal("100"), 5);
        assertThat(result).isEqualTo(1);
    }

    @Test
    @DisplayName("FLOOR 나눗셈: 현금 1000, 가격 300이면 3을 반환한다 (반올림 아님)")
    void calculate_FloorDivision_Cash1000Price300_ReturnsThreeNotRoundedUp() {
        int result = calculator.calculate(new BigDecimal("1000"), new BigDecimal("300"), 10);
        assertThat(result).isEqualTo(3); // 1000/300 = 3.33... → FLOOR → 3, not 4
    }

    @Test
    @DisplayName("감당 가능한 수량이 원하는 수량보다 크면 desired로 클램프된다")
    void calculate_AffordableGreaterThanDesired_ClampedToDesired() {
        // 현금 1000, 가격 10 → 감당 가능 100주, desired=5 → 5로 클램프
        int result = calculator.calculate(new BigDecimal("1000"), new BigDecimal("10"), 5);
        assertThat(result).isEqualTo(5);
    }

    @Test
    @DisplayName("감당 가능한 수량이 원하는 수량보다 적으면 부분 수량을 반환한다")
    void calculate_AffordableLessThanDesired_ReturnsPartial() {
        // 현금 250, 가격 100 → 감당 가능 2주, desired=5 → 2 반환
        int result = calculator.calculate(new BigDecimal("250"), new BigDecimal("100"), 5);
        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("cash>=price 가드를 통과하면 FLOOR division 결과(affordableRaw)는 항상 1 이상이 되어 " +
            "affordableRaw<=0 분기는 사실상 도달 불가능하다 (desired=0 클램프 경계로 최종 min() 분기를 확인)")
    void calculate_AffordableRawNonPositiveBranchIsUnreachableGivenEarlierGuards_DesiredZeroBoundary() {
        // cash.compareTo(price) < 0 가드를 통과하려면 cash >= price(price>0)여야 하고, 이 경우
        // cash.divide(price, 0, FLOOR)는 수학적으로 항상 몫이 1 이상이므로 affordableRaw<=0 분기는
        // 이 가드 뒤에서는 실제로 도달할 수 없다. 대신 마지막 min(affordable, desired) 클램프의
        // desired=0 경계를 검증한다: 감당 가능한 수량이 있어도 원하는 수량이 0이면 결과는 0이다.
        int result = calculator.calculate(new BigDecimal("1000"), new BigDecimal("100"), 0);
        assertThat(result).isZero();
    }
}
