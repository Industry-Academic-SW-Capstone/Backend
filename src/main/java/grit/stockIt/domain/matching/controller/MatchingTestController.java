// MatchingTestController.java
package grit.stockIt.domain.matching.controller; // (패키지 위치는 알맞게 조정하세요)

import grit.stockIt.domain.matching.dto.MarketFillTestRequest;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import org.springframework.context.annotation.Profile;
@Profile({"local", "dev"})
@Slf4j
@RestController
@RequestMapping("/api/test")
@Tag(name = "TEST API", description = "[개발용] 매칭/체결 테스트 API")
@RequiredArgsConstructor
public class MatchingTestController {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 지정가 주문을 강제로 체결시키는 테스트용 API
     */
    @Operation(summary = "[TEST] 지정가 주문 강제 체결",
            description = "대기 중인 지정가 주문(Maker)에 대해 체결(Taker) 이벤트를 발생시킵니다.")
    @PostMapping("/fill")
    public String triggerMarketFill(@RequestBody MarketFillTestRequest request) {

        // 1. DTO -> Spring Event Message 변환
        LimitOrderFillEventMessage eventMessage = new LimitOrderFillEventMessage(
                request.stockCode(),
                UUID.randomUUID().toString(), // 이벤트 ID (테스트이므로 랜덤 생성)
                request.orderMethod(),       // Taker의 주문 방식 (BUY or SELL)
                request.price(),             // 체결 가격
                request.quantity(),          // 체결 수량
                System.currentTimeMillis()   // 이벤트 시간
        );

        // 2. Spring Event 발행
        // (LimitOrderEventPublisher가 이 이벤트를 수신하여 Redis에 넣고 MatchingService를 호출)
        eventPublisher.publishEvent(eventMessage);

        log.info("[TEST] 체결 이벤트 발행: stockCode={}, method={}, price={}, quantity={}",
                request.stockCode(), request.orderMethod(), request.price(), request.quantity());

        return "OK: 체결 이벤트가 발행되었습니다. (Stock: " + request.stockCode() + ")";
    }
}