package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import grit.stockIt.domain.test.dto.MockExecutionAcceptedResponse;
import grit.stockIt.domain.test.dto.MockExecutionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 체결 이벤트 주입 진입점. prod 에서는 노출되지 않는다.
//
// KIS 피드와 같은 입구(FillCommandPublisher)를 탄다. 다른 길로 넣으면 측정이 실제 경로를 재지 못한다.
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Profile("!prod")
@Tag(name = "Load Test", description = "부하 테스트 전용 API (스테이징 환경 전용)")
public class LoadTestController {

    private final FillCommandPublisher fillCommandPublisher;

    // 큐에 넣었으면 202다 — 체결은 아직이고, 브로커에 기록돼 사라지지 않는다는 뜻이다.
    // 넣지 못한 이벤트는 503으로 돌려준다. 200으로 돌려주면 브로커 장애가 호출자의 에러율에 잡히지 않는다.
    @PostMapping("/mock-execution")
    @Operation(
            summary = "체결 이벤트 주입",
            description = "KIS 실시간 피드와 같은 입구(FillCommandPublisher)로 체결 이벤트를 흘려보낸다. "
                    + "큐에 넣으면 202, 넣지 못하면 503."
    )
    public ResponseEntity<?> injectMockExecution(@Valid @RequestBody MockExecutionRequest request) {
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                request.eventId(),
                request.orderMethod(),
                request.price(),
                request.quantity(),
                request.eventTimestamp()
        );

        return switch (fillCommandPublisher.publish(request.stockCode(), event)) {
            case FillDispatchResult.Queued queued -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new MockExecutionAcceptedResponse(request.eventId(), queued.partition(), queued.offset()));
            case FillDispatchResult.Failed failed -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        };
    }
}
