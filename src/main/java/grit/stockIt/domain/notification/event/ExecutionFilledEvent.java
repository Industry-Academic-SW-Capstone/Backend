package grit.stockIt.domain.notification.event;

import java.math.BigDecimal;

// 체결 완료 이벤트
public record ExecutionFilledEvent(
        Long executionId,
        Long orderId,
        Long accountId,
        Long memberId,  // Member 조회를 위해 추가
        String stockCode,
        String stockName,
        BigDecimal price,
        Integer quantity,
        String orderMethod  // BUY, SELL
) {
}

