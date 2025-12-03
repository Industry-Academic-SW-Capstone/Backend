package grit.stockIt.domain.notification.controller;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.service.AdminNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/notifications")
@Tag(name = "admin-notifications", description = "관리자 알림 API")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService adminNotificationService;
    private final MemberRepository memberRepository;

    @Operation(
            summary = "전체 사용자에게 알림 전송 (관리자 전용)",
            description = "모든 사용자에게 시스템 알림을 전송합니다. 알림 권한이 꺼진 사용자에게도 전송됩니다."
    )
    @PostMapping("/broadcast")
    public ResponseEntity<Map<String, Object>> broadcastNotification(
            @Valid @RequestBody BroadcastNotificationRequest request) {
        
        log.info("=== 관리자 전체 알림 전송 요청: title={}, body={} ===", request.title(), request.body());
        
        // 모든 회원 조회
        List<Member> allMembers = memberRepository.findAll();
        log.info("전체 회원 수: {}", allMembers.size());
        
        // FCM 토큰이 있는 회원 수 확인
        long membersWithTokenCount = allMembers.stream()
                .filter(Member::hasFcmToken)
                .count();
        
        log.info("FCM 토큰이 등록된 회원 수: {}", membersWithTokenCount);
        
        // 비동기로 알림 전송 및 DB 저장 (모든 회원에게 DB 저장, FCM 토큰 있는 회원에게만 푸시 전송)
        adminNotificationService.sendBroadcastNotification(allMembers, request.title(), request.body());
        
        // 즉시 응답 반환
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "알림 전송이 시작되었습니다.");
        response.put("total_members", allMembers.size());
        response.put("members_with_token", membersWithTokenCount);
        response.put("title", request.title());
        response.put("body", request.body());
        
        return ResponseEntity.ok(response);
    }
    
    // 요청 DTO
    public record BroadcastNotificationRequest(
            @NotBlank(message = "제목은 필수입니다.")
            String title,
            @NotBlank(message = "내용은 필수입니다.")
            String body
    ) {}
}

