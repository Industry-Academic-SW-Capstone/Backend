package grit.stockIt.global.controller.dto;

import grit.stockIt.global.dlt.DeadLetterService;

import java.util.List;

// 아직 처리하지 않은 DLT 메시지. pendingCount 는 전부의 개수, messages 는 앞에서부터 요청한 개수까지.
public record DeadLetterBatchResponse(
        String topic,
        int pendingCount,
        List<DeadLetterResponse> messages
) {

    public static DeadLetterBatchResponse from(DeadLetterService.DeadLetterBatch batch) {
        return new DeadLetterBatchResponse(batch.topic(), batch.pendingCount(),
                batch.messages().stream().map(DeadLetterResponse::from).toList());
    }
}
