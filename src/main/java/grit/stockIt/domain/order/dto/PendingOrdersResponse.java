package grit.stockIt.domain.order.dto;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 대기 주문 조회 응답 DTO
public record PendingOrdersResponse(
        List<PendingOrderItem> orders
) {
    public record PendingOrderItem(
            Long orderId,                     // 주문 ID
            String stockCode,                 // 종목 코드
            String stockName,                 // 종목명
            String marketType,                // 시장 구분
            OrderMethod orderMethod,          // 매수/매도
            BigDecimal price,                 // 주문 가격
            int quantity,                     // 주문 수량
            int remainingQuantity,            // 남은 수량
            LocalDateTime createdAt           // 주문 시간
    ) {}
}

