package grit.stockIt.domain.notification.controller;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.dto.NotificationPageResponse;
import grit.stockIt.domain.notification.dto.NotificationResponse;
import grit.stockIt.domain.notification.dto.UnreadCountResponse;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.service.NotificationService;
import grit.stockIt.global.exception.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/notifications")
@Tag(name = "notifications", description = "알림 관련 API")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final MemberRepository memberRepository;

    @Operation(summary = "알림 목록 조회", description = "사용자의 알림 목록을 페이징하여 조회합니다.")
    @GetMapping
    public ResponseEntity<NotificationPageResponse> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long memberId = getCurrentMemberId();
        NotificationPageResponse response = notificationService.getNotifications(memberId, page, size);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "읽지 않은 알림 개수", description = "사용자의 읽지 않은 알림 개수를 조회합니다.")
    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> getUnreadCount() {
        Long memberId = getCurrentMemberId();
        UnreadCountResponse response = notificationService.getUnreadCount(memberId);
        return ResponseEntity.ok(response);
    }

    // 명확성을 위해 동사로 함.
    @Operation(summary = "알림 읽음 처리", description = "특정 알림을 읽음 상태로 변경합니다.")
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable Long notificationId) {
        Long memberId = getCurrentMemberId();
        notificationService.markAsRead(notificationId, memberId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "전체 알림 읽음 처리", description = "사용자의 모든 알림을 읽음 상태로 변경합니다.")
    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllAsRead() {
        Long memberId = getCurrentMemberId();
        notificationService.markAllAsRead(memberId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "알림 삭제", description = "특정 알림을 삭제합니다.")
    @DeleteMapping("/{notificationId}")
    public ResponseEntity<Void> deleteNotification(@PathVariable Long notificationId) {
        Long memberId = getCurrentMemberId();
        notificationService.deleteNotification(notificationId, memberId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "타입별 알림 조회", description = "특정 타입의 알림 목록을 조회합니다.")
    @GetMapping("/filter")
    public ResponseEntity<List<NotificationResponse>> getNotificationsByType(
            @RequestParam NotificationType type) {
        Long memberId = getCurrentMemberId();
        List<NotificationResponse> response = notificationService.getNotificationsByType(memberId, type);
        return ResponseEntity.ok(response);
    }

    // 현재 인증된 사용자의 memberId 가져오기
    private Long getCurrentMemberId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new BadRequestException("인증되지 않은 사용자입니다.");
        }

        // UserDetails에서 이메일 추출
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String email = userDetails.getUsername();

        // 이메일로 Member 조회하여 memberId 반환
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("회원을 찾을 수 없습니다."));

        return member.getMemberId();
    }
}

