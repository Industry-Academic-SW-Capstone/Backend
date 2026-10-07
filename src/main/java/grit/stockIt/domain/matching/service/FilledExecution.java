package grit.stockIt.domain.matching.service;

// 체결 한 건. 정산 요청을 계좌 키로 보내려면 체결 트랜잭션 안에서 계좌를 함께 알아 둬야 한다.
public record FilledExecution(Long executionId, Long accountId) {
}
