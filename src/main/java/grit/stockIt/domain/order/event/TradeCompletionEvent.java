package grit.stockIt.domain.order.event;

import grit.stockIt.domain.order.entity.OrderMethod;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class TradeCompletionEvent {

    private final Long memberId;
    private final Long accountId;
    private final String stockCode;
    private final OrderMethod orderMethod; // BUY or SELL

    private final int filledQuantity;
    private final BigDecimal filledPrice;
    private final BigDecimal filledAmount; // 총 체결 금액

    // --- ⬇️ [추가] 미션 판별을 위한 핵심 데이터 ⬇️ ---
    private final BigDecimal profitAmount; // 수익금 (매도 시에만 유효, 매수는 0)
    private final BigDecimal profitRate;   // 수익률 (매도 시에만 유효, 단위 %)
    private final int holdingDays;         // 보유 일수 (매도 시에만 유효)

    private final BigDecimal buyAveragePrice; // 내가 샀던 가격 (평단가) [NEW!]

    public TradeCompletionEvent(Long memberId, Long accountId, String stockCode,
                                OrderMethod orderMethod, int filledQuantity, BigDecimal filledPrice,
                                BigDecimal profitAmount, BigDecimal profitRate, int holdingDays, BigDecimal buyAveragePrice) {
        this.memberId = memberId;
        this.accountId = accountId;
        this.stockCode = stockCode;
        this.orderMethod = orderMethod;
        this.filledQuantity = filledQuantity;
        this.filledPrice = filledPrice;
        this.filledAmount = filledPrice.multiply(BigDecimal.valueOf(filledQuantity));

        this.buyAveragePrice = buyAveragePrice != null ? buyAveragePrice : BigDecimal.ZERO;
        // 추가된 필드 초기화 (null 들어오면 기본값 처리)
        this.profitAmount = profitAmount != null ? profitAmount : BigDecimal.ZERO;
        this.profitRate = profitRate != null ? profitRate : BigDecimal.ZERO;
        this.holdingDays = holdingDays;
    }

    // (기존 생성자 호환용)
    public TradeCompletionEvent(Long memberId, Long accountId, String stockCode,
                                OrderMethod orderMethod, int filledQuantity, BigDecimal filledPrice) {
        this(memberId, accountId, stockCode, orderMethod, filledQuantity, filledPrice,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO); // [수정] 마지막 인자 0 -> BigDecimal.ZERO
    }
}