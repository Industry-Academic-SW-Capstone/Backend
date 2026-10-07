package grit.stockIt.domain.settlement.queue;

// settlement.requests 메시지. filledAt 은 체결 커밋 → 정산 완료 지연을 재는 기준이다.
public record SettlementRequest(Long executionId, Long accountId, long filledAt) {

    public void validate() {
        if (executionId == null || accountId == null) {
            throw new InvalidSettlementRequestException("처리할 수 없는 정산 요청입니다. " + this);
        }
    }
}
