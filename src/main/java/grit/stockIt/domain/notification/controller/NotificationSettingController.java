package grit.stockIt.domain.notification.controller;

import grit.stockIt.domain.notification.dto.FcmTokenRequest;
import grit.stockIt.domain.notification.dto.NotificationSettingsRequest;
import grit.stockIt.domain.notification.service.NotificationSettingService;
import grit.stockIt.global.jwt.LoginMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

// 푸시 수신 설정 API. 프론트가 쓰는 경로(/api/members/...)는 그대로 두고 처리는 알림이 한다.
@Slf4j
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
@Tag(name = "Member", description = "회원 관리 API")
public class NotificationSettingController {

    private final NotificationSettingService notificationSettingService;

    @Operation(summary = "FCM 토큰 등록/업데이트", description = "FCM 푸시 알림을 받기 위한 토큰을 등록하거나 업데이트합니다.")
    @PutMapping("/fcm-token")
    public ResponseEntity<String> registerFcmToken(@Valid @RequestBody FcmTokenRequest request) {
        Optional<Long> memberId = currentMemberId();
        if (memberId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        notificationSettingService.updateFcmToken(memberId.get(), request.getFcmToken());
        log.info("FCM 토큰 등록/업데이트: memberId={}", memberId.get());
        return ResponseEntity.ok("FCM 토큰이 등록되었습니다.");
    }

    @Operation(summary = "FCM 토큰 삭제", description = "등록된 FCM 토큰을 삭제합니다.")
    @DeleteMapping("/fcm-token")
    public ResponseEntity<String> removeFcmToken() {
        Optional<Long> memberId = currentMemberId();
        if (memberId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        notificationSettingService.removeFcmToken(memberId.get());
        log.info("FCM 토큰 삭제: memberId={}", memberId.get());
        return ResponseEntity.ok("FCM 토큰이 삭제되었습니다.");
    }

    @Operation(summary = "알림 설정 변경", description = "체결 알림 설정을 변경합니다.")
    @PatchMapping("/notification-settings")
    public ResponseEntity<String> updateNotificationSettings(@Valid @RequestBody NotificationSettingsRequest request) {
        Optional<Long> memberId = currentMemberId();
        if (memberId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        notificationSettingService.updateExecutionNotification(memberId.get(), request.isExecutionNotificationEnabled());
        log.info("알림 설정 변경: memberId={}, enabled={}", memberId.get(), request.isExecutionNotificationEnabled());
        return ResponseEntity.ok("알림 설정이 변경되었습니다.");
    }

    private Optional<Long> currentMemberId() {
        return LoginMember.current().map(LoginMember::memberId);
    }
}
