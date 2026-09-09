package grit.stockIt.domain.test.dto;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

/**
 * 부하 테스트용 가짜 체결 이벤트 주입 요청.
 *
 * <p>KIS 실시간 피드가 발행하는 {@code LimitOrderFillEventMessage}와 같은 형태다
 * ({@code KisWebSocketClient} 참고).
 */
public record MockExecutionRequest(
        String stockCode,
        String eventId,
        OrderMethod orderMethod,
        BigDecimal price,
        Integer quantity,
        Long eventTimestamp
) {
}
