package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import grit.stockIt.domain.test.dto.MockExecutionAcceptedResponse;
import grit.stockIt.domain.test.dto.MockExecutionRequest;
import grit.stockIt.domain.test.dto.MockExecutionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 체결 이벤트 주입 진입점. prod 에서는 노출되지 않는다.
//
// KIS 피드와 같은 입구(FillCommandPublisher)를 탄다. 종목 락을 건너뛰면 실제 경로에서 가장 비싼 구간인
// 직렬화가 통째로 빠져 측정이 무의미해진다.
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Profile("!prod")
@Tag(name = "Load Test", description = "부하 테스트 전용 API (스테이징 환경 전용)")
public class LoadTestController {

    private final FillCommandPublisher fillCommandPublisher;

    // 처리하지도 큐에 넣지도 못한 이벤트는 503으로 돌려준다. 200으로 돌려주면 락 대기 초과나 커넥션 고갈이
    // 호출자의 에러율에 잡히지 않아 포화 상태가 정상으로 보인다.
    // 큐에 넣었으면 202다 — 체결은 아직이고, 브로커에 기록돼 사라지지 않는다는 뜻이다.
    @PostMapping("/mock-execution")
    @Operation(
            summary = "체결 이벤트 주입",
            description = "KIS 실시간 피드와 같은 입구(FillCommandPublisher)로 체결 이벤트를 흘려보낸다. "
                    + "바로 처리하면 200, 큐에 넣으면 202, 둘 다 못 하면 503."
    )
    public ResponseEntity<?> injectMockExecution(@Valid @RequestBody MockExecutionRequest request) {
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                request.eventId(),
                request.orderMethod(),
                request.price(),
                request.quantity(),
                request.eventTimestamp()
        );

        long startNanos = System.nanoTime();
        FillDispatchResult result = fillCommandPublisher.publish(request.stockCode(), event);
        long handleMs = (System.nanoTime() - startNanos) / 1_000_000L;

        return switch (result) {
            case FillDispatchResult.Processed processed ->
                    ResponseEntity.ok(new MockExecutionResponse(processed.filledOrders(), handleMs));
            case FillDispatchResult.Queued queued -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new MockExecutionAcceptedResponse(request.eventId(), queued.partition(), queued.offset()));
            case FillDispatchResult.Failed failed ->
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new MockExecutionResponse(0, handleMs));
        };
    }
}
