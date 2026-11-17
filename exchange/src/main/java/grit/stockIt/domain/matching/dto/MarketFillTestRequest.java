package grit.stockIt.domain.matching.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import grit.stockIt.domain.order.entity.OrderMethod;
import java.math.BigDecimal;

// 테스트용이므로 record로 간단하게 만듭니다.
public record MarketFillTestRequest(
        @JsonProperty("stockCode")
        String stockCode,     // 예: "005930"
        @JsonProperty("orderMethod")
        OrderMethod orderMethod, // 이 체결이 매수인지 매도인지 (BUY / SELL)

        BigDecimal price,       // 체결 가격
        int quantity          // 체결 수량
) {
}