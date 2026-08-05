package grit.stockIt.domain.mission.service;

import static org.assertj.core.api.Assertions.assertThat;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * [B-1 단위 특성화] MissionConditionEvaluator — Phase A 통합 경유 특성화를 직접 호출로 승격.
 * 전 조건 분기표(매수 전용 / 매도 전용 / 공통 / 비거래)를 그대로 고정한다.
 */
@DisplayName("MissionConditionEvaluator 단위 특성화 테스트")
class MissionConditionEvaluatorTest {

    private final MissionConditionEvaluator evaluator = new MissionConditionEvaluator();

    private static Mission missionOf(MissionConditionType type) {
        return Mission.builder().conditionType(type).build();
    }

    private static TradeCompletionEvent eventOf(OrderMethod method) {
        return new TradeCompletionEvent(1L, 1L, "005930", method, 1, new BigDecimal("10000"));
    }

    // --- isMissionConditionMatches: 조건 분기표 ---

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class, names = {"BUY_COUNT", "BUY_AMOUNT"})
    void 매수전용_조건은_BUY에만_매칭된다(MissionConditionType type) {
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.BUY))).isTrue();
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.SELL))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class,
            names = {"SELL_COUNT", "SELL_AMOUNT", "PROFIT_RATE", "DAILY_PROFIT_COUNT", "PROFIT_AMOUNT"})
    void 매도전용_조건은_SELL에만_매칭된다(MissionConditionType type) {
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.SELL))).isTrue();
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.BUY))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class,
            names = {"TRADE_COUNT", "TOTAL_TRADE_AMOUNT", "DAILY_TRADE_COUNT"})
    void 공통_조건은_매수와_매도_모두에_매칭된다(MissionConditionType type) {
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.BUY))).isTrue();
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.SELL))).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"BUY_COUNT", "BUY_AMOUNT",
                    "SELL_COUNT", "SELL_AMOUNT", "PROFIT_RATE", "DAILY_PROFIT_COUNT", "PROFIT_AMOUNT",
                    "TRADE_COUNT", "TOTAL_TRADE_AMOUNT", "DAILY_TRADE_COUNT"})
    void 비거래_조건은_어떤_주문에도_매칭되지_않는다(MissionConditionType type) {
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.BUY))).isFalse();
        assertThat(evaluator.isMissionConditionMatches(missionOf(type), eventOf(OrderMethod.SELL))).isFalse();
    }

    // --- isCumulativeType / isThresholdType: 유형 분기표 ---

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class,
            names = {"TRADE_COUNT", "BUY_COUNT", "SELL_COUNT", "BUY_AMOUNT", "SELL_AMOUNT",
                    "TOTAL_TRADE_AMOUNT", "DAILY_PROFIT_COUNT", "DAILY_TRADE_COUNT", "PROFIT_RATE"})
    void 누적형_조건_9종만_isCumulativeType이_참이다(MissionConditionType type) {
        assertThat(evaluator.isCumulativeType(type)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = MissionConditionType.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"TRADE_COUNT", "BUY_COUNT", "SELL_COUNT", "BUY_AMOUNT", "SELL_AMOUNT",
                    "TOTAL_TRADE_AMOUNT", "DAILY_PROFIT_COUNT", "DAILY_TRADE_COUNT", "PROFIT_RATE"})
    void 누적형이_아닌_조건은_isCumulativeType이_거짓이다(MissionConditionType type) {
        assertThat(evaluator.isCumulativeType(type)).isFalse();
    }

    @Test
    void 달성형은_PROFIT_AMOUNT_하나뿐이다() {
        for (MissionConditionType type : MissionConditionType.values()) {
            assertThat(evaluator.isThresholdType(type))
                    .as("type %s", type)
                    .isEqualTo(type == MissionConditionType.PROFIT_AMOUNT);
        }
    }
}
