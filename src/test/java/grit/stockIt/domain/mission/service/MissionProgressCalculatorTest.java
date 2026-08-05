package grit.stockIt.domain.mission.service;

import static org.assertj.core.api.Assertions.assertThat;

import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * [B-1 단위 특성화] MissionProgressCalculator — Phase A 통합 경유 특성화를 직접 호출로 승격.
 * 반올림(HALF_UP)·SELL 전제·sqrt(profit/10)·0 이하 -> 0 등 관찰된 현재 동작을 그대로 고정한다.
 */
@DisplayName("MissionProgressCalculator 단위 특성화 테스트")
class MissionProgressCalculatorTest {

    private final MissionProgressCalculator calculator = new MissionProgressCalculator();

    private static TradeCompletionEvent buyEvent(int quantity, String price) {
        return new TradeCompletionEvent(1L, 1L, "005930", OrderMethod.BUY, quantity, new BigDecimal(price));
    }

    private static TradeCompletionEvent sellEvent(int quantity, String sellPrice, String buyAveragePrice) {
        return new TradeCompletionEvent(1L, 1L, "005930", OrderMethod.SELL, quantity, new BigDecimal(sellPrice),
                null, null, 0, new BigDecimal(buyAveragePrice));
    }

    // --- calculateIncreaseValue: 누적형 유형별 분기표 ---

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class,
            names = {"TRADE_COUNT", "BUY_COUNT", "SELL_COUNT", "DAILY_TRADE_COUNT"})
    void 횟수형은_항상_1씩_증가한다(MissionConditionType type) {
        assertThat(calculator.calculateIncreaseValue(type, buyEvent(3, "10000"))).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class,
            names = {"BUY_AMOUNT", "SELL_AMOUNT", "TOTAL_TRADE_AMOUNT"})
    void 금액형은_체결금액_정수부만큼_증가한다(MissionConditionType type) {
        // 체결금액 = 10,000 x 3 = 30,000
        assertThat(calculator.calculateIncreaseValue(type, buyEvent(3, "10000"))).isEqualTo(30000);
    }

    @Test
    void 일일익절횟수는_SELL이면서_수익일_때만_1이다() {
        // SELL + 익절 (12,000 > 10,000)
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.DAILY_PROFIT_COUNT, sellEvent(1, "12000", "10000"))).isEqualTo(1);
        // SELL + 손절
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.DAILY_PROFIT_COUNT, sellEvent(1, "9000", "10000"))).isZero();
        // SELL + 본전 (동가)
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.DAILY_PROFIT_COUNT, sellEvent(1, "10000", "10000"))).isZero();
        // BUY (평단가 기본값 0보다 체결가가 커도 매도가 아니므로 0)
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.DAILY_PROFIT_COUNT, buyEvent(1, "10000"))).isZero();
    }

    @Test
    void 수익률_누적은_SELL_전제이고_HALF_UP_반올림된_정수_수익률이다() {
        // 특성화: BUY면 0
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.PROFIT_RATE, buyEvent(1, "10000"))).isZero();
        // 5.5% 수익 -> 6 (HALF_UP)
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "10550", "10000"))).isEqualTo(6);
        // 4.4% 수익 -> 4
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "10440", "10000"))).isEqualTo(4);
        // 특성화: -10% 손실 -> -10 (진행도 깎임, 현재 동작 그대로)
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "9000", "10000"))).isEqualTo(-10);
        // 특성화: 평단가 0이면 계산 불가 -> 0
        assertThat(calculator.calculateIncreaseValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "10000", "0"))).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"TRADE_COUNT", "BUY_COUNT", "SELL_COUNT", "DAILY_TRADE_COUNT",
                    "BUY_AMOUNT", "SELL_AMOUNT", "TOTAL_TRADE_AMOUNT", "DAILY_PROFIT_COUNT", "PROFIT_RATE"})
    void 누적형이_아닌_조건의_증가값은_0이다(MissionConditionType type) {
        assertThat(calculator.calculateIncreaseValue(type, sellEvent(1, "12000", "10000"))).isZero();
    }

    // --- calculateThresholdValue: 달성형 분기표 ---

    @Test
    void 달성형_계산은_SELL이_아니면_무조건_0이다() {
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_AMOUNT, buyEvent(1, "10000"))).isZero();
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_RATE, buyEvent(1, "10000"))).isZero();
    }

    @Test
    void 달성형_수익률은_HALF_UP_반올림되고_평단가_0이면_0이다() {
        // 4.9% -> 5
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "10490", "10000"))).isEqualTo(5);
        // 4.4% -> 4
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "10440", "10000"))).isEqualTo(4);
        // 손실 -10% -> -10
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "9000", "10000"))).isEqualTo(-10);
        // 평단가 0 -> 0
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_RATE, sellEvent(1, "10000", "0"))).isZero();
    }

    @Test
    void 달성형_수익금은_체결금액에서_매입원가를_뺀_정수부다() {
        // (12,000 x 2) - (10,000 x 2) = 4,000
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_AMOUNT, sellEvent(2, "12000", "10000"))).isEqualTo(4000);
        // 손실이면 음수 그대로: (9,000 x 2) - (10,000 x 2) = -2,000
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.PROFIT_AMOUNT, sellEvent(2, "9000", "10000"))).isEqualTo(-2000);
    }

    @Test
    void 달성형이_아닌_조건은_SELL이어도_0이다() {
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.TRADE_COUNT, sellEvent(1, "12000", "10000"))).isZero();
        assertThat(calculator.calculateThresholdValue(
                MissionConditionType.HOLDING_DAYS, sellEvent(1, "12000", "10000"))).isZero();
    }

    // --- calculateScoreFromProfit: sqrt(profit/10), 0 이하 -> 0 ---

    @Test
    void 수익금_점수환산은_sqrt_profit_나누기10이고_0이하는_0이다() {
        assertThat(calculator.calculateScoreFromProfit(-500)).isZero();
        assertThat(calculator.calculateScoreFromProfit(0)).isZero();
        // 특성화: 1 -> sqrt(0.1) = 0.316 -> 0 (int 절사)
        assertThat(calculator.calculateScoreFromProfit(1)).isZero();
        // 10 -> sqrt(1) = 1
        assertThat(calculator.calculateScoreFromProfit(10)).isEqualTo(1);
        // 1,000 -> sqrt(100) = 10
        assertThat(calculator.calculateScoreFromProfit(1000)).isEqualTo(10);
        // 특성화: 절사 — 3,599 -> sqrt(359.9) = 18.97 -> 18
        assertThat(calculator.calculateScoreFromProfit(3599)).isEqualTo(18);
    }
}
