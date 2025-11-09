package grit.stockIt.domain.auth.controller;

import grit.stockIt.domain.auth.dto.KakaoLoginResponse;
import grit.stockIt.domain.auth.dto.KakaoSignupCompleteRequest;
import grit.stockIt.domain.auth.service.KakaoAuthService;
import grit.stockIt.global.jwt.JwtToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/auth/kakao")
@RequiredArgsConstructor
@Tag(name = "Kakao Auth", description = "카카오 인증 API")
public class KakaoAuthController {

    private final KakaoAuthService kakaoAuthService;

    @Operation(summary = "카카오 로그인 콜백", description = "카카오 OAuth 콜백 처리")
    @GetMapping("/callback")
    public CompletableFuture<ResponseEntity<KakaoLoginResponse>> kakaoCallback(@RequestParam String code) {
        return kakaoAuthService.login(code)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    log.error("카카오 로그인 처리 중 예외 발생: {}", ex.toString(), ex);
                    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
                });
    }

    @Operation(summary = "카카오 회원가입 완료", description = "신규 회원 가입 완료 처리")
    @PostMapping("/signup/complete")
    public ResponseEntity<JwtToken> completeSignup(@Valid @RequestBody KakaoSignupCompleteRequest req) {
        JwtToken token = kakaoAuthService.completeSignup(req.getEmail(), req.getName(), req.getProfileImage());
        return ResponseEntity.ok(token);
    }

    @Operation(summary = "카카오 로그아웃", description = "카카오 로그아웃 처리")
    @PostMapping("/logout")
    public CompletableFuture<ResponseEntity<String>> logout() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return CompletableFuture.completedFuture(
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("인증이 필요합니다.")
            );
        }

        String email = auth.getName();
        return kakaoAuthService.logout(email)
                .thenApply(v -> ResponseEntity.ok("로그아웃되었습니다."));
    }

    // IllegalArgumentException 핸들러 추가
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getMessage());
    }
}