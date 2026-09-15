package grit.stockIt.domain.test.dto;

import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

// 체결 이벤트 주입 요청. KIS 실시간 피드가 전달하는 것과 같은 필드다.
public record MockExecutionRequest(
        @NotBlank String stockCode,
        @NotBlank String eventId,
        @NotNull OrderMethod orderMethod,
        @NotNull @Positive BigDecimal price,
        @NotNull @Positive Integer quantity,
        @NotNull Long eventTimestamp
) {
}
