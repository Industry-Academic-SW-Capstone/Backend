package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * [B-1 추출] 미션 진행도/점수 계산 순수 로직.
 * Spring 컨텍스트 없이 new로 직접 생성하여 단위 테스트할 수 있다.
 */
@Slf4j
@Component
public class MissionProgressCalculator {

    public int calculateIncreaseValue(MissionConditionType type, TradeCompletionEvent event) {
        return switch (type) {
            case TRADE_COUNT, BUY_COUNT, SELL_COUNT, DAILY_TRADE_COUNT -> 1;

            case BUY_AMOUNT, SELL_AMOUNT, TOTAL_TRADE_AMOUNT ->
                    event.getFilledAmount().intValue();

            case DAILY_PROFIT_COUNT -> {
                // 매도(SELL)이면서, 체결가가 평단가보다 크면 익절 (1회 증가)
                boolean isSell = event.getOrderMethod() == OrderMethod.SELL;
                boolean isProfit = event.getFilledPrice().compareTo(event.getBuyAveragePrice()) > 0;
                yield (isSell && isProfit) ? 1 : 0;
            }

            // [신규 이동] 수익률 누적 계산
            case PROFIT_RATE -> {
                if (event.getOrderMethod() != OrderMethod.SELL) yield 0;

                BigDecimal sellPrice = event.getFilledPrice();
                BigDecimal avgBuyPrice = event.getBuyAveragePrice();

                if (avgBuyPrice == null || avgBuyPrice.compareTo(BigDecimal.ZERO) == 0) {
                    yield 0;
                }

                // 수익률 공식: ((매도가 - 평단가) / 평단가) * 100
                BigDecimal profitRate = sellPrice.subtract(avgBuyPrice)
                        .divide(avgBuyPrice, 4, java.math.RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                // 예: 5.5% 수익 -> 6점 증가 (반올림)
                // 예: -10% 손실 -> -10점 (진행도 깎임) -> 원치 않으시면 Math.max(0, ...) 처리 필요
                yield profitRate.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
            }

            default -> 0;
        };
    }

    // [수정] 값 계산 시 반올림 적용 (선택 사항이나 권장)
    public int calculateThresholdValue(MissionConditionType type, TradeCompletionEvent event) {
        // 매도가 아니면 수익률/수익금 계산 불가
        if (event.getOrderMethod() != OrderMethod.SELL) return 0;

        // 1. 수익률 (PROFIT_RATE) 계산
        if (type == MissionConditionType.PROFIT_RATE) {
            // [수정] event.getProfitRate()를 신뢰하지 않고 직접 계산 로직을 우선 사용

            BigDecimal sellPrice = event.getFilledPrice();     // 매도 체결가
            BigDecimal avgBuyPrice = event.getBuyAveragePrice(); // 평단가

            // 평단가가 0이거나 없으면 계산 불가 (0 리턴)
            if (avgBuyPrice == null || avgBuyPrice.compareTo(BigDecimal.ZERO) == 0) {
                log.warn("수익률 계산 실패: 평단가가 0입니다. StockCode={}", event.getStockCode());
                return 0;
            }

            // 공식: ((매도가 - 평단가) / 평단가) * 100
            // 예: 매도가 10500, 평단가 10000 -> (500 / 10000) * 100 = 5%
            BigDecimal profitRate = sellPrice.subtract(avgBuyPrice)
                    .divide(avgBuyPrice, 4, java.math.RoundingMode.HALF_UP) // 소수점 4자리까지 확보 (0.0500)
                    .multiply(BigDecimal.valueOf(100)); // 백분율 변환 (5.00)

            // 로그로 계산 과정 출력 (디버깅용)
            log.info("수익률 계산: ({} - {}) / {} * 100 = {}%",
                    sellPrice, avgBuyPrice, avgBuyPrice, profitRate);

            // 소수점 반올림하여 정수로 반환 (예: 4.9% -> 5%, 4.4% -> 4%)
            return profitRate.setScale(0, java.math.RoundingMode.HALF_UP).intValue();
        }

        // 2. 수익금 (PROFIT_AMOUNT) 계산
        if (type == MissionConditionType.PROFIT_AMOUNT) {
            // 수익금은 직접 계산: (판 금액 - (평단가 * 수량))
            BigDecimal totalSellAmount = event.getFilledAmount();
            BigDecimal totalBuyCost = event.getBuyAveragePrice()
                    .multiply(BigDecimal.valueOf(event.getFilledQuantity()));

            BigDecimal profitAmount = totalSellAmount.subtract(totalBuyCost);

            return profitAmount.intValue();
        }

        return 0;
    }

    /**
     * [신규] 수익금을 점수로 환산하는 헬퍼 메서드
     * - 공식: Score = sqrt(max(0, TotalProfit))
     * - 수익금이 마이너스(손실 중)라면 0점으로 처리
     */
    public int calculateScoreFromProfit(int totalProfit) {
        if (totalProfit <= 0) return 0;
        return (int) Math.sqrt(totalProfit / 10.0);
    }
}
