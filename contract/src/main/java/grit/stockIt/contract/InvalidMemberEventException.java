package grit.stockIt.contract;

// 다시 시도해도 처리할 수 없는 회원 이벤트. 재시도하지 않고 DLT 로 보낸다.
public class InvalidMemberEventException extends RuntimeException {

    public InvalidMemberEventException(String message) {
        super(message);
    }
}
