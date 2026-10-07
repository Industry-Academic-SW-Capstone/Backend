package grit.stockIt.domain.settlement.queue;

// 다시 시도해도 처리할 수 없는 정산 요청. 재시도하지 않고 DLT 로 보낸다.
public class InvalidSettlementRequestException extends RuntimeException {

    public InvalidSettlementRequestException(String message) {
        super(message);
    }
}
