package grit.stockIt.domain.test.dto;

/**
 * 가짜 체결 주입 결과. 부하 테스트가 읽는 지표를 담는다.
 *
 * <p>전역 SNAKE_CASE Jackson 설정에 따라 {@code execution_count},
 * {@code queue_depth}, {@code duration_ms}로 직렬화된다.
 *
 * @param executionCount 이번 이벤트로 체결된 주문 수.
 *                       요청 수 대비 이 값이 낮으면 락 경합으로 처리가 밀리고 있다는 뜻이다.
 * @param queueDepth     처리 후 남은 큐 길이. <b>포화 지점을 판정하는 핵심 지표.</b>
 *                       단조 증가하면 유입이 처리 능력을 초과한 것이다.
 * @param durationMs     큐 적재부터 정산 완료까지 걸린 시간
 */
public record MockExecutionResponse(
        int executionCount,
        long queueDepth,
        long durationMs
) {
}
