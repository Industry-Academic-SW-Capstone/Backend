package grit.stockIt.domain.test.dto;

/**
 * 가짜 체결 주입 결과.
 *
 * <p>전역 SNAKE_CASE Jackson 설정에 따라 {@code queue_depth}, {@code enqueue_ms}로 직렬화된다.
 *
 * <p><b>체결 건수는 담지 않는다.</b> 소비를 워커가 전담하므로 요청 시점에는 이 이벤트가
 * 언제 체결될지 알 수 없다. 체결 건수와 체결 지연은 앱 지표로 관측한다.
 *
 * <pre>
 *   matching_executions_total          체결된 주문 수 (누적)
 *   matching_event_latency_seconds     이벤트 도착 → 체결 완료
 * </pre>
 *
 * @param queueDepth 적재 후 큐 길이. <b>포화 지점을 판정하는 핵심 지표.</b>
 *                   단조 증가하면 유입이 처리 능력을 초과한 것이다.
 * @param enqueueMs  시세 갱신부터 큐 적재까지 걸린 시간. 수신 경로의 비용이며,
 *                   체결까지의 시간이 아니다.
 */
public record MockExecutionResponse(
        long queueDepth,
        long enqueueMs
) {
}
