package grit.stockIt.domain.order.dto;

import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record LimitOrderCreateRequest(
        @NotNull(message = "계좌 ID는 필수입니다.")
        Long accountId,
        @NotBlank(message = "종목 코드는 필수입니다.")
        String stockCode,
        @NotNull(message = "지정가 가격은 필수입니다.")
        BigDecimal price,
        @Positive(message = "주문 수량은 0보다 커야 합니다.")
        int quantity,
        @NotNull(message = "매수/매도 구분은 필수입니다.")
        OrderMethod orderMethod
) {
}

