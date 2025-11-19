package grit.stockIt.domain.member.controller;

import grit.stockIt.domain.member.dto.FcmTokenRequest;
import grit.stockIt.domain.member.dto.MemberLoginRequest;
import grit.stockIt.domain.member.dto.MemberResponse;
import grit.stockIt.domain.member.dto.MemberSignupRequest;
import grit.stockIt.domain.member.dto.NotificationSettingsRequest;
import grit.stockIt.domain.member.dto.MemberUpdateRequest;
import grit.stockIt.domain.member.service.LocalMemberService;
import grit.stockIt.global.jwt.JwtToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;  // 추가
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@Slf4j  // 추가
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
@Tag(name = "Member", description = "회원 관리 API")
public class MemberController {

    private final LocalMemberService memberService; 
    @Operation(summary = "회원가입", description = "새로운 회원을 등록합니다.")
    @PostMapping("/signup")
    public ResponseEntity<MemberResponse> signup(@Valid @RequestBody MemberSignupRequest request) {
        MemberResponse response = memberService.signup(request);
        return ResponseEntity.ok(response);
    }

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인합니다.")
    @PostMapping("/login")
    public ResponseEntity<JwtToken> login(@Valid @RequestBody MemberLoginRequest request) {
        JwtToken token = memberService.login(request);
        return ResponseEntity.ok(token);
    }

    @Operation(summary = "이메일 존재 여부 확인", description = "주어진 이메일이 회원으로 등록되어 있는지 확인합니다.")
    @GetMapping("/exists")
    public ResponseEntity<java.util.Map<String, Boolean>> existsByEmail(@RequestParam("email") String email) {
        boolean exists = memberService.existsByEmail(email);
        return ResponseEntity.ok(java.util.Collections.singletonMap("exists", exists));
    }

    @Operation(summary = "로그아웃", description = "현재 로그인한 사용자를 로그아웃합니다.")
    @PostMapping("/logout")
    public ResponseEntity<String> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        
        String email = auth.getName();
        log.info("로그아웃: {}", email);
        
        return ResponseEntity.ok("로그아웃되었습니다.");
    }

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 정보를 반환합니다.")
    @GetMapping("/me")
    public ResponseEntity<MemberResponse> getMyInfo() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = auth.getName();
        MemberResponse resp = memberService.getMemberByEmail(email);
        return ResponseEntity.ok(resp);
    }

    @Operation(summary = "내 정보 수정", description = "현재 로그인한 사용자의 프로필 및 설정을 수정합니다.")
    @PutMapping("/me")
    public ResponseEntity<MemberResponse> updateMyInfo(@RequestBody MemberUpdateRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String email = auth.getName();
        MemberResponse updated = memberService.updateMember(email, request);
        return ResponseEntity.ok(updated);
    }

    @Operation(summary = "FCM 토큰 등록/업데이트", description = "FCM 푸시 알림을 받기 위한 토큰을 등록하거나 업데이트합니다.")
    @PutMapping("/fcm-token")
    public ResponseEntity<String> registerFcmToken(@Valid @RequestBody FcmTokenRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        
        String email = auth.getName();
        memberService.updateFcmToken(email, request.getFcmToken());
        log.info("FCM 토큰 등록/업데이트: email={}", email);
        
        return ResponseEntity.ok("FCM 토큰이 등록되었습니다.");
    }

    @Operation(summary = "FCM 토큰 삭제", description = "등록된 FCM 토큰을 삭제합니다.")
    @DeleteMapping("/fcm-token")
    public ResponseEntity<String> removeFcmToken() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        
        String email = auth.getName();
        memberService.removeFcmToken(email);
        log.info("FCM 토큰 삭제: email={}", email);
        
        return ResponseEntity.ok("FCM 토큰이 삭제되었습니다.");
    }

    @Operation(summary = "알림 설정 변경", description = "체결 알림 설정을 변경합니다.")
    @PatchMapping("/notification-settings")
    public ResponseEntity<String> updateNotificationSettings(@Valid @RequestBody NotificationSettingsRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증되지 않은 요청입니다.");
        }
        
        String email = auth.getName();
        memberService.updateExecutionNotificationSettings(email, request.isExecutionNotificationEnabled());
        log.info("알림 설정 변경: email={}, enabled={}", email, request.isExecutionNotificationEnabled());
        
        return ResponseEntity.ok("알림 설정이 변경되었습니다.");
    }

    // @Valid 실패 시: 400 + 에러메시지 한 줄
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("잘못된 요청입니다.");
        return ResponseEntity.badRequest().body(msg);
    }

    // 서비스에서 던진 IllegalArgumentException도 동일 포맷
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}