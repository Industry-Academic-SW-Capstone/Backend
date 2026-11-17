package grit.stockIt.contract;

// 다시 시도해도 처리할 수 없는 결과 통보. 재시도하지 않고 DLT 로 보낸다.
public class InvalidExecutionReportException extends RuntimeException {

    public InvalidExecutionReportException(String message) {
        super(message);
    }
}
