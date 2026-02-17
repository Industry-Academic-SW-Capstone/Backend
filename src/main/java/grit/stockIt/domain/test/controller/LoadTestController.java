package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.LimitOrderExecutionService;
import grit.stockIt.domain.order.entity.OrderMethod;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * 부하 테스트 전용 컨트롤러
 * 스테이징 환경에서만 사용 (프로파일로 제한 권장)
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
@Profile("!prod")
@Tag(name = "Load Test", description = "부하 테스트 전용 API (스테이징 환경 전용)")
public class LoadTestController {

    private final LimitOrderExecutionService limitOrderExecutionService;

    /**
     * 가짜 체결 데이터를 주입하여 체결 로직의 성능을 테스트
     * 
     * 요청 예시:
     * {
     *   "stock_code": "005930",
     *   "event_id": "uuid",
     *   "order_method": "BUY",
     *   "price": 70000,
     *   "quantity": 100,
     *   "event_timestamp": 1234567890
     * }
     */
    @PostMapping("/mock-execution")
    @Operation(
        summary = "가짜 체결 데이터 주입",
        description = "부하 테스트를 위한 가짜 체결 데이터를 주입합니다. 실제 체결 로직이 동작합니다."
    )
    public ResponseEntity<String> injectMockExecution(
            @Valid @RequestBody MockExecutionRequest request) {
        
        // 인증 확인 (선택사항 - 부하 테스트 시에는 인증 없이도 가능하도록 설정 가능)
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("인증되지 않은 체결 데이터 주입 요청");
            // 부하 테스트를 위해 인증 없이도 허용할 수 있음
            // return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            // LimitOrderFillEvent 생성
            LimitOrderFillEvent event = new LimitOrderFillEvent(
                    request.eventId(),
                    request.orderMethod(),
                    request.price(),
                    request.quantity(),
                    request.eventTimestamp()
            );

            // 체결 로직 실행 (성능 측정 대상)
            long startTime = System.currentTimeMillis();
            List<?> executions = limitOrderExecutionService.distributeEvent(
                    request.stockCode(),
                    event
            );
            long duration = System.currentTimeMillis() - startTime;

            log.info("체결 처리 완료: stockCode={}, quantity={}, executions={}, duration={}ms",
                    request.stockCode(), request.quantity(), executions.size(), duration);

            return ResponseEntity.ok(String.format(
                    "체결 처리 완료: %d건, 처리 시간: %dms",
                    executions.size(),
                    duration
            ));

        } catch (Exception e) {
            log.error("체결 데이터 주입 실패: stockCode={}", request.stockCode(), e);
            return ResponseEntity.status(500)
                    .body("체결 처리 실패: " + e.getMessage());
        }
    }

    /**
     * 체결 데이터 주입 요청 DTO
     */
    public record MockExecutionRequest(
            String stockCode,
            String eventId,
            OrderMethod orderMethod,
            BigDecimal price,
            Integer quantity,
            Long eventTimestamp
    ) {
    }
}



