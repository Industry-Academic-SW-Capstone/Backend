package grit.stockIt.domain.matching.service;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;

// 체결 한 건. 결과 통보에 실을 내용을 체결 트랜잭션 안에서 모아 둔다 — 커밋 뒤에는 엔티티를 읽지 않는다.
// cumQuantity·leavesQuantity 는 이 체결 뒤 주문의 누적 체결 수량과 남은 수량이다 — 증권사가 주문 상태와 완료를 이것으로 정한다.
public record FilledExecution(Long executionId, Long orderId, Long accountId, String stockCode,
                              OrderMethod orderMethod, BigDecimal price, int quantity,
                              int cumQuantity, int leavesQuantity) {

    public ExecutionReport toReport(long filledAt) {
        return ExecutionReport.fill(executionId, orderId, accountId, stockCode, orderMethod, price, quantity,
                cumQuantity, leavesQuantity, filledAt);
    }
}
