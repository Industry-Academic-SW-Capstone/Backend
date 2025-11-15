package grit.stockIt.domain.order.event; // (이벤트 패키지는 알맞게 수정하세요)

import grit.stockIt.domain.order.entity.OrderMethod;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class TradeCompletionEvent {

    private final Long memberId;      // 미션 주체 (Member)
    private final Long accountId;     // 보상 지급 대상 (Account)
    private final String stockCode;   // 종목 코드
    private final OrderMethod orderMethod; // BUY or SELL

    private final int filledQuantity; // 체결 수량
    private final BigDecimal filledPrice;  // 체결 단가
    private final BigDecimal filledAmount; // 총 체결 금액 (수량 * 단가)

    public TradeCompletionEvent(Long memberId, Long accountId, String stockCode,
                                OrderMethod orderMethod, int filledQuantity, BigDecimal filledPrice) {
        this.memberId = memberId;
        this.accountId = accountId;
        this.stockCode = stockCode;
        this.orderMethod = orderMethod;
        this.filledQuantity = filledQuantity;
        this.filledPrice = filledPrice;
        this.filledAmount = filledPrice.multiply(BigDecimal.valueOf(filledQuantity));
    }
}