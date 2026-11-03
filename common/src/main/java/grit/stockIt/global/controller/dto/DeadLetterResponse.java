package grit.stockIt.global.controller.dto;

import grit.stockIt.global.dlt.DeadLetterService;

// DLT 메시지 하나. exception·exceptionMessage 는 DLT 로 보낸 쪽이 헤더에 남긴 실패 사유다.
public record DeadLetterResponse(
        int partition,
        long offset,
        String originalTopic,
        String key,
        String exception,
        String exceptionMessage,
        String payload
) {

    public static DeadLetterResponse from(DeadLetterService.DeadLetter letter) {
        return new DeadLetterResponse(letter.partition(), letter.offset(), letter.originalTopic(), letter.key(),
                letter.exception(), letter.exceptionMessage(), letter.payload());
    }
}
