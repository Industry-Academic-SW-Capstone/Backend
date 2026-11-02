package grit.stockIt.global.controller;

import grit.stockIt.global.controller.dto.DeadLetterActionResponse;
import grit.stockIt.global.controller.dto.DeadLetterBatchResponse;
import grit.stockIt.global.dlt.DeadLetterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 관리자 API(/api/admin/** 는 ROLE_ADMIN 만). 실패 사유를 보고 원래 토픽으로 다시 보내거나 버린다.
@RestController
@RequestMapping("/api/admin/dlt")
@Tag(name = "dlt-admin", description = "DLT 조회·재전송 관리자 API")
@RequiredArgsConstructor
public class DeadLetterAdminController {

    private static final int MAX_LIMIT = 200;

    private final DeadLetterService deadLetterService;

    @Operation(summary = "DLT 별 미처리 개수", description = "다루는 DLT 마다 아직 처리하지 않은 메시지 수를 돌려줍니다.")
    @GetMapping
    public ResponseEntity<List<DeadLetterBatchResponse>> summary() {
        return ResponseEntity.ok(deadLetterService.topics().stream()
                .map(topic -> DeadLetterBatchResponse.from(deadLetterService.pending(topic, 0)))
                .toList());
    }

    @Operation(summary = "DLT 미처리 메시지 조회", description = "실패 사유·원래 토픽·키·내용을 앞에서부터 limit 개까지 돌려줍니다.")
    @GetMapping("/{topic}")
    public ResponseEntity<DeadLetterBatchResponse> pending(@PathVariable String topic,
                                                           @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.max(0, Math.min(limit, MAX_LIMIT));
        return ResponseEntity.ok(DeadLetterBatchResponse.from(deadLetterService.pending(topic, bounded)));
    }

    @Operation(summary = "DLT 재전송", description = "미처리 메시지를 모두 원래 토픽에 같은 키로 다시 보냅니다. 받는 쪽이 중복을 거릅니다.")
    @PostMapping("/{topic}/replay")
    public ResponseEntity<DeadLetterActionResponse> replay(@PathVariable String topic) {
        return ResponseEntity.ok(new DeadLetterActionResponse(topic, "REPLAYED", deadLetterService.replay(topic)));
    }

    @Operation(summary = "DLT 건너뜀", description = "미처리 메시지를 보내지 않고 처리한 것으로 표시합니다. 고칠 수 없는 메시지에 씁니다.")
    @PostMapping("/{topic}/skip")
    public ResponseEntity<DeadLetterActionResponse> skip(@PathVariable String topic) {
        return ResponseEntity.ok(new DeadLetterActionResponse(topic, "SKIPPED", deadLetterService.skip(topic)));
    }
}
