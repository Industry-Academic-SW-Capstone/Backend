package grit.stockIt.domain.account.dto;

import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 특정 종목의 보유 정보 및 주문 내역 통합 응답 DTO
public record MyStockResponse(
        String stockCode,
        String stockName,
        String marketType,
        BigDecimal currentPrice,
        
        HoldingInfo holding,                          // 보유 정보 (보유하지 않으면 null)
        List<OrderHistoryItem> orderHistory          // 주문 내역 (빈 리스트 가능)
) {
    public record HoldingInfo(
            BigDecimal averagePricePerShare,          // 1주 평균금액
            int quantity,                             // 보유 수량 (정수만, 소수점 없음)
            BigDecimal currentPrice,                  // 현재가
            BigDecimal totalValue,                    // 총 금액 (quantity × currentPrice)
            BigDecimal investmentPrincipal,           // 투자 원금 (quantity × averagePricePerShare)
            BigDecimal profitLoss,                    // 평가 손익 (totalValue - investmentPrincipal)
            BigDecimal profitRate                     // 수익률 ((profitLoss / investmentPrincipal) × 100)
    ) {}
    
    public record OrderHistoryItem(
            Integer year,                             // 연도 (같은 연도면 null 가능)
            String date,                              // 날짜 (MM.dd 형식)
            Long orderId,                             // 주문 ID
            OrderMethod orderMethod,                  // BUY or SELL
            int quantity,                             // 주문 수량
            BigDecimal orderPrice,                    // 주문 가격
            BigDecimal executionPrice,                // 실제 체결 가격 (평균 체결가, null 가능)
            int executedQuantity,                     // 실제 체결 수량
            BigDecimal totalAmount,                   // 총 거래 금액 (executionPrice × executedQuantity, null 가능)
            OrderStatus status,                       // 주문 상태
            LocalDateTime createdAt                   // 주문 시간
    ) {}
}

