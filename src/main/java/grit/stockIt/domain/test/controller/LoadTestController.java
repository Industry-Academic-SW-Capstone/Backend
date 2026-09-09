package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.MatchingEventQueue;
import grit.stockIt.domain.matching.service.LimitOrderEventPublisher;
import grit.stockIt.domain.test.dto.MockExecutionRequest;
import grit.stockIt.domain.test.dto.MockExecutionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 부하 테스트 전용 컨트롤러. {@code @Profile("!prod")}로 프로덕션에서는 비활성이다.
 *
 * <p>KIS 실시간 피드 없이 가짜 체결 이벤트를 주입해 매칭 경로를 그대로 태운다.
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Profile("!prod")
@Tag(name = "Load Test", description = "부하 테스트 전용 API (스테이징 환경 전용)")
public class LoadTestController {

    private final LimitOrderEventPublisher limitOrderEventPublisher;
    private final MatchingEventQueue matchingEventQueue;

    /**
     * 가짜 체결 이벤트를 주입해 매칭 경로 전체를 실행한다.
     *
     * <p><b>KIS 피드가 들어올 때와 같은 경로를 탄다</b> — 시세 갱신 → 큐 적재 → 종목별 락 →
     * 큐 소비 → 정산. 예전에는 {@code LimitOrderExecutionService.distributeEvent}를 직접
     * 호출해 <b>큐와 락을 건너뛰었고</b>, 그 결과 종목별 직렬화가 측정에서 빠져
     * 단일 종목 처리량이 실제보다 높게 나왔다.
     *
     * <p>KIS 피드가 타는 것과 <b>같은 메서드</b>({@code LimitOrderEventPublisher#publish})를
     * 호출한다. Spring 이벤트 디스패치만 건너뛰므로 경로가 갈라지지 않는다.
     */
    @PostMapping("/mock-execution")
    @Operation(
            summary = "가짜 체결 데이터 주입",
            description = "체결 이벤트를 큐에 넣고 매칭까지 실행합니다. "
                    + "종목별 락과 이벤트 큐를 포함한 실제 경로를 그대로 탑니다."
    )
    public ResponseEntity<MockExecutionResponse> injectMockExecution(
            @Valid @RequestBody MockExecutionRequest request) {

        LimitOrderFillEvent event = new LimitOrderFillEvent(
                request.eventId(),
                request.orderMethod(),
                request.price(),
                request.quantity(),
                request.eventTimestamp()
        );

        long startTime = System.currentTimeMillis();

        List<Execution> executions = limitOrderEventPublisher.publish(request.stockCode(), event);

        long duration = System.currentTimeMillis() - startTime;
        long queueDepth = matchingEventQueue.size(request.stockCode());

        // 락 획득에 실패하면 이벤트가 큐에 남은 채 빈 리스트가 돌아온다(200 OK).
        // 처리량만 보면 이 상태를 놓치므로 큐 길이를 함께 응답에 담는다.
        log.debug("체결 주입 완료: stockCode={} quantity={} executions={} queueDepth={} duration={}ms",
                request.stockCode(), request.quantity(), executions.size(), queueDepth, duration);

        return ResponseEntity.ok(new MockExecutionResponse(executions.size(), queueDepth, duration));
    }
}
