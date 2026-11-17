package grit.stockIt.domain.order.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MarketOrderCreateRequest(
        @NotNull(message = "계좌 ID는 필수입니다.")
        @JsonProperty("account_id")
        Long accountId,
        @NotBlank(message = "종목 코드는 필수입니다.")
        @JsonProperty("stock_code")
        String stockCode,
        @Positive(message = "주문 수량은 0보다 커야 합니다.")
        int quantity,
        @NotNull(message = "매수/매도 구분은 필수입니다.")
        @JsonProperty("order_method")
        OrderMethod orderMethod
) {
}

