package grit.stockIt.domain.matching.queue;

// 다시 시도해도 처리할 수 없는 명령. 재시도하지 않고 DLT 로 보낸다.
public class InvalidMatchingCommandException extends RuntimeException {

    public InvalidMatchingCommandException(String message) {
        super(message);
    }
}
