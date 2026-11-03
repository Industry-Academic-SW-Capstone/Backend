package grit.stockIt.domain.test.dto;

// 체결 이벤트가 큐에 기록됐다. partition/offset 은 워커 처리 위치와 대조할 때 쓴다.
public record MockExecutionAcceptedResponse(String eventId, int partition, long offset) {
}
