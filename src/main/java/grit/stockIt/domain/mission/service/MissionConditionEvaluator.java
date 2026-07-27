package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import org.springframework.stereotype.Component;

/**
 * [B-1 추출] 미션 조건 판정 순수 로직.
 * Spring 컨텍스트 없이 new로 직접 생성하여 단위 테스트할 수 있다.
 */
@Component
public class MissionConditionEvaluator {

    public boolean isMissionConditionMatches(Mission mission, TradeCompletionEvent event) {
        MissionConditionType type = mission.getConditionType();
        OrderMethod method = event.getOrderMethod();

        // 매수 전용
        if (type == MissionConditionType.BUY_COUNT || type == MissionConditionType.BUY_AMOUNT)
            return method == OrderMethod.BUY;

        // 매도 전용
        if (type == MissionConditionType.SELL_COUNT || type == MissionConditionType.SELL_AMOUNT ||
                type == MissionConditionType.PROFIT_RATE || type == MissionConditionType.DAILY_PROFIT_COUNT ||
                type == MissionConditionType.PROFIT_AMOUNT)
            return method == OrderMethod.SELL;

        // 공통
        if (type == MissionConditionType.TRADE_COUNT || type == MissionConditionType.TOTAL_TRADE_AMOUNT ||
                type == MissionConditionType.DAILY_TRADE_COUNT)
            return true;

        return false;
    }

    public boolean isCumulativeType(MissionConditionType type) {
        return switch (type) {
            case TRADE_COUNT, BUY_COUNT, SELL_COUNT,
                 BUY_AMOUNT, SELL_AMOUNT,
                 TOTAL_TRADE_AMOUNT, DAILY_PROFIT_COUNT, DAILY_TRADE_COUNT,

                 PROFIT_RATE // [추가] 수익률도 이제 차곡차곡 쌓는 '누적형'입니다.
                    -> true;

            default -> false;
        };
    }

    public boolean isThresholdType(MissionConditionType type) {
        // HOLDING_DAYS는 스케줄러가 처리하므로 제외
        return switch (type) {
            case PROFIT_AMOUNT -> true;
            default -> false;
        };
    }
}
