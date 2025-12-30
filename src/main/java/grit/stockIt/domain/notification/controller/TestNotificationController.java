package grit.stockIt.domain.notification.controller;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.service.FcmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/test/notifications")
@Tag(name = "test-notifications", description = "알림 테스트 API (개발용)")
@RequiredArgsConstructor
public class TestNotificationController {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;

    @Operation(
            summary = "테스트 or 관리자 전용 알림 전송"
    )
    @PostMapping
    public ResponseEntity<Map<String, Object>> sendTestNotification(
            @RequestParam Long memberId,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String body) {

        log.info("=== 테스트 FCM 알림 전송 API 호출: memberId={}, title={}, body={} ===", memberId, title, body);

        // Member 조회
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("Member를 찾을 수 없습니다: memberId=" + memberId));

        // FCM 토큰 확인
        if (!member.hasFcmToken()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "FCM 토큰이 등록되지 않은 사용자입니다.");
            response.put("memberId", memberId);
            return ResponseEntity.badRequest().body(response);
        }

        // FCM 메시지 데이터 구성
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("type", "SYSTEM");
        data.put("test", "true");
        data.put("timestamp", String.valueOf(System.currentTimeMillis()));

        // FCM 푸시 알림 전송
        boolean success = fcmService.sendExecutionNotification(member.getFcmToken(), data);

        // 응답
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("message", success ? "FCM 알림이 전송되었습니다." : "FCM 알림 전송에 실패했습니다.");
        response.put("data", Map.of(
                "memberId", memberId,
                "title", title,
                "body", body,
                "fcmToken", member.getFcmToken().substring(0, Math.min(20, member.getFcmToken().length())) + "..."
        ));

        return ResponseEntity.ok(response);
    }
}

