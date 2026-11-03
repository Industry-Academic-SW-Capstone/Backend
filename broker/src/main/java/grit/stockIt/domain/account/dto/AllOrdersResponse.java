package grit.stockIt.domain.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 전체 주문 목록 조회 응답 DTO (날짜별 그룹화)
public record AllOrdersResponse(
        List<DateGroup> ordersByDate              // 날짜별 그룹화된 주문 목록
) {
    public record DateGroup(
            Integer year,                          // 연도 (올해면 null)
            String date,                           // 날짜 (MM.dd 형식)
            List<OrderItem> orders                 // 해당 날짜의 주문 목록
    ) {}
    
    @JsonInclude(JsonInclude.Include.ALWAYS)  // null 값도 항상 포함
    public record OrderItem(
            Long orderId,                          // 주문 ID
            String stockCode,                      // 종목 코드
            String stockName,                      // 종목명
            OrderType orderType,                   // MARKET or LIMIT
            OrderMethod orderMethod,               // BUY or SELL
            int quantity,                          // 주문 수량
            BigDecimal orderPrice,                 // 주문 가격 (시장가면 null)
            BigDecimal executionPrice,             // 실제 체결 가격 (평균 체결가, null 가능)
            int executedQuantity,                  // 실제 체결 수량
            BigDecimal totalAmount,                // 총 거래 금액 (executionPrice × executedQuantity, null 가능)
            OrderStatus status,                    // 주문 상태
            LocalDateTime createdAt                // 주문 시간
    ) {}
}

