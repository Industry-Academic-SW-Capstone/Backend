package grit.stockIt.domain.test.controller;

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
            description = "체결 이벤트를 큐에 적재합니다. 소비는 매칭 워커가 맡습니다. "
                    + "KIS 실시간 피드와 같은 경로를 그대로 탑니다."
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

        limitOrderEventPublisher.publish(request.stockCode(), event);

        long enqueueMs = System.currentTimeMillis() - startTime;
        long queueDepth = matchingEventQueue.size(request.stockCode());

        // 소비는 워커가 맡으므로 여기서는 적재 결과만 안다. 밀림은 큐 길이로 드러난다.
        log.debug("체결 이벤트 적재: stockCode={} quantity={} queueDepth={} enqueueMs={}",
                request.stockCode(), request.quantity(), queueDepth, enqueueMs);

        return ResponseEntity.ok(new MockExecutionResponse(queueDepth, enqueueMs));
    }
}
