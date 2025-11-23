package grit.stockIt.domain.notification.controller;

import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/test/notifications")
@Tag(name = "test-notifications", description = "알림 테스트 API (개발용)")
@RequiredArgsConstructor
public class TestNotificationController {

    private final ApplicationEventPublisher eventPublisher;

    @Operation(
            summary = "[테스트] 체결 알림 발행",
            description = "간단하게 체결 알림을 테스트합니다. memberId만 입력하면 됩니다."
    )
    @PostMapping("/execution")
    public ResponseEntity<Map<String, Object>> testExecutionNotification(
            @RequestParam Long memberId) {

        log.info("=== 테스트 알림 API 호출: memberId={} ===", memberId);

        // 체결 이벤트 생성
        ExecutionFilledEvent event = new ExecutionFilledEvent(
                999L,                           // executionId
                888L,                           // orderId
                1L,                             // accountId
                memberId,                       // memberId
                1L,                             // contestId
                "제 1회 테스트 대회",             // contestName
                "005930",                       // stockCode
                "삼성전자",                      // stockName
                new BigDecimal("82000"),        // price
                50,                             // quantity
                "BUY"                           // orderMethod
        );

        // 이벤트 발행
        eventPublisher.publishEvent(event);

        log.info("체결 알림 이벤트 발행 완료: memberId={}", memberId);

        // 응답
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "체결 알림이 발행되었습니다");
        response.put("data", Map.of(
                "memberId", memberId,
                "stockName", "삼성전자",
                "quantity", 50,
                "price", 82000,
                "orderMethod", "BUY"
        ));

        return ResponseEntity.ok(response);
    }
}

