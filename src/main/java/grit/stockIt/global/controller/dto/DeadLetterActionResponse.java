package grit.stockIt.global.controller.dto;

// 재전송·건너뜀 결과. count 는 처리한 메시지 수다.
public record DeadLetterActionResponse(
        String topic,
        String action,
        int count
) {
}
