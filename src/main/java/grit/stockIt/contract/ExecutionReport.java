package grit.stockIt.contract;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

// exchange.reports 메시지. 받는 쪽이 거래소 테이블을 다시 읽지 않도록 결과 내용을 모두 싣는다.
// 필드는 추가만 한다 — 이름을 바꾸거나 지우면 배포 순서에 따라 받는 쪽이 읽지 못한다.
// FILL 은 체결 번호·가격·수량과 그 체결 뒤의 누적 체결 수량·남은 수량을 채운다(FIX 의 CumQty·LeavesQty).
// CANCELED·EXPIRED·REJECTED 는 canceledQuantity(오더북에서 빠진 남은 수량)를 채운다.
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
        int canceledQuantity,
        int cumQuantity,
        int leavesQuantity
) {

    public static ExecutionReport fill(Long executionId, Long orderId, Long accountId, String stockCode,
                                       OrderMethod orderMethod, BigDecimal price, int quantity,
                                       int cumQuantity, int leavesQuantity, long filledAt) {
        return new ExecutionReport(ExecutionReportType.FILL, executionId, orderId, accountId, stockCode,
                orderMethod, price, quantity, filledAt, 0, cumQuantity, leavesQuantity);
    }

    public static ExecutionReport of(ExecutionReportType type, Long orderId, Long accountId, String stockCode,
                                     OrderMethod orderMethod, int canceledQuantity, long reportedAt) {
        if (type == ExecutionReportType.FILL) {
            throw new IllegalArgumentException("체결은 fill() 로 만든다.");
        }
        return new ExecutionReport(type, null, orderId, accountId, stockCode, orderMethod, null, 0, reportedAt,
                canceledQuantity, 0, 0);
    }

    public void validate() {
        if (type == null || orderId == null || accountId == null || stockCode == null || orderMethod == null
                || !validForType()) {
            throw new InvalidExecutionReportException("처리할 수 없는 결과 통보입니다. " + this);
        }
    }

    // 주문을 오더북에서 빼는 결과인가 — 증권사는 주문을 끝내고 남은 홀딩을 푼다.
    public boolean endsOrder() {
        return type == ExecutionReportType.CANCELED || type == ExecutionReportType.EXPIRED
                || type == ExecutionReportType.REJECTED;
    }

    private boolean validForType() {
        return switch (type) {
            case FILL -> executionId != null && price != null && price.signum() > 0 && quantity > 0
                    && cumQuantity >= quantity && leavesQuantity >= 0;
            case ACCEPTED -> true;
            case CANCELED, EXPIRED, REJECTED -> canceledQuantity >= 0;
        };
    }
}
