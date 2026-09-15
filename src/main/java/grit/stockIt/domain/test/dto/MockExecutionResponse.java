package grit.stockIt.domain.test.dto;

// 체결 이벤트 주입 결과.
//
//   filled    이 이벤트로 체결된 주문 수
//   handleMs  요청 스레드가 붙들려 있던 시간(밀리초). 여기서는 체결 완료까지를 뜻한다
public record MockExecutionResponse(int filled, long handleMs) {
}
