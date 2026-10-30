package grit.stockIt.contract;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

// exchange.reports 메시지. 받는 쪽이 거래소 테이블을 다시 읽지 않도록 결과 내용을 모두 싣는다.
// 필드는 추가만 한다 — 이름을 바꾸거나 지우면 배포 순서에 따라 받는 쪽이 읽지 못한다.
// FILL 은 체결 번호·가격·수량을, CANCELED·EXPIRED 는 canceledQuantity(그 시점의 남은 수량)를 채운다.
// filledAt 은 결과가 커밋된 시각이다 — 체결이면 체결 커밋 → 정산 완료 지연을 재는 기준이다.
public record ExecutionReport(
        ExecutionReportType type,
        Long executionId,
        Long orderId,
        Long accountId,
        String stockCode,
        OrderMethod orderMethod,
        BigDecimal price,
        int quantity,
        long filledAt,
        int canceledQuantity
) {

    public static ExecutionReport fill(Long executionId, Long orderId, Long accountId, String stockCode,
                                       OrderMethod orderMethod, BigDecimal price, int quantity, long filledAt) {
        return new ExecutionReport(ExecutionReportType.FILL, executionId, orderId, accountId, stockCode,
                orderMethod, price, quantity, filledAt, 0);
    }

    public static ExecutionReport ended(ExecutionReportType type, Long orderId, Long accountId, String stockCode,
                                        OrderMethod orderMethod, int canceledQuantity, long endedAt) {
        if (type == ExecutionReportType.FILL) {
            throw new IllegalArgumentException("체결은 fill() 로 만든다.");
        }
        return new ExecutionReport(type, null, orderId, accountId, stockCode, orderMethod, null, 0, endedAt,
                canceledQuantity);
    }

    public void validate() {
        if (type == null || orderId == null || accountId == null || stockCode == null || orderMethod == null
                || !validForType()) {
            throw new InvalidExecutionReportException("처리할 수 없는 결과 통보입니다. " + this);
        }
    }

    private boolean validForType() {
        return switch (type) {
            case FILL -> executionId != null && price != null && price.signum() > 0 && quantity > 0;
            case CANCELED, EXPIRED -> canceledQuantity >= 0;
        };
    }
}
