package grit.stockIt.domain.auth.controller;

import grit.stockIt.domain.auth.dto.KakaoSignupCompleteRequest;
import grit.stockIt.domain.auth.service.KakaoAuthService;
import grit.stockIt.global.jwt.JwtService;
import grit.stockIt.global.jwt.JwtToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth/kakao")
@RequiredArgsConstructor
@Tag(name = "Kakao Auth", description = "카카오 인증 API")
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;
    private final JwtService jwtService;

    @Operation(summary = "카카오 로그인 콜백", description = "카카오 인가 코드로 로그인 또는 회원가입 정보 반환")
    @GetMapping("/callback")
    public ResponseEntity<?> kakaoCallback(@RequestParam String code) {
        Object result = kakaoAuthService.login(code);
        
        // JwtToken이면 기존 회원, KakaoSignupResponse면 신규 회원
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "카카오 회원가입 완료", description = "신규 회원 가입 완료 처리")
    @PostMapping("/signup/complete")
    public ResponseEntity<JwtToken> completeSignup(@Valid @RequestBody KakaoSignupCompleteRequest req) {
        JwtToken token = kakaoAuthService.completeSignup(
                req.getEmail(),
                req.getName(),
                req.getProfileImage()
        );
        return ResponseEntity.ok(token);
    }

    @Operation(summary = "카카오 로그아웃", description = "카카오 로그아웃 처리")
    @PostMapping("/logout")
    public ResponseEntity<String> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body("인증이 필요합니다.");
        }
        String email = auth.getName(); // JWT 필터가 설정한 사용자 식별자
        kakaoAuthService.logout(email);
        return ResponseEntity.ok("로그아웃되었습니다.");
    }

    // 예외 처리
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAny(Exception ex) {
        return ResponseEntity.badRequest().body(ex.getMessage() != null ? ex.getMessage() : "요청 처리 중 오류");
    }
}