package grit.stockIt.global.controller;

import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.controller.dto.KisTokenRefreshResponse;
import grit.stockIt.global.controller.dto.KisTokenStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// 관리자 API
@Slf4j
@RestController
@RequestMapping("/api/admin/kis-tokens")
@Tag(name = "kis-token-admin", description = "KIS 토큰 관리자 API")
@RequiredArgsConstructor
public class KisTokenAdminController {

    private final KisTokenManager kisTokenManager;
    private final StringRedisTemplate redisTemplate;

    private static final String ACCESS_TOKEN_KEY = "kis:access_token";
    private static final String APPROVAL_KEY_KEY = "kis:approval_key";

    @Operation(summary = "Access Token 갱신", description = "KIS API Access Token을 강제로 갱신하고 Redis에 저장합니다.")
    @PostMapping("/access-token/refresh")
    public ResponseEntity<KisTokenRefreshResponse> refreshAccessToken() {
        try {
            log.info("관리자 요청: Access Token 갱신");
            String newToken = kisTokenManager.refreshAccessToken();
            return ResponseEntity.ok(KisTokenRefreshResponse.success("ACCESS_TOKEN", newToken));
        } catch (Exception e) {
            log.error("Access Token 갱신 실패", e);
            return ResponseEntity.internalServerError()
                    .body(KisTokenRefreshResponse.error("Access Token 갱신 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "Approval Key 갱신", description = "KIS WebSocket Approval Key를 강제로 갱신하고 Redis에 저장합니다.")
    @PostMapping("/approval-key/refresh")
    public ResponseEntity<KisTokenRefreshResponse> refreshApprovalKey() {
        try {
            log.info("관리자 요청: Approval Key 갱신");
            String newKey = kisTokenManager.refreshApprovalKey();
            return ResponseEntity.ok(KisTokenRefreshResponse.success("APPROVAL_KEY", newKey));
        } catch (Exception e) {
            log.error("Approval Key 갱신 실패", e);
            return ResponseEntity.internalServerError()
                    .body(KisTokenRefreshResponse.error("Approval Key 갱신 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "모든 토큰 갱신", description = "Access Token과 Approval Key를 모두 갱신합니다.")
    @PostMapping("/refresh-all")
    public ResponseEntity<Object> refreshAllTokens() {
        try {
            log.info("관리자 요청: 모든 토큰 갱신");
            
            String accessToken = kisTokenManager.refreshAccessToken();
            String approvalKey = kisTokenManager.refreshApprovalKey();
            
            return ResponseEntity.ok(new AllTokensRefreshResponse(
                    "모든 토큰 갱신 성공",
                    true,
                    KisTokenRefreshResponse.success("ACCESS_TOKEN", accessToken),
                    KisTokenRefreshResponse.success("APPROVAL_KEY", approvalKey)
            ));
        } catch (Exception e) {
            log.error("토큰 갱신 실패", e);
            return ResponseEntity.internalServerError()
                    .body(new AllTokensRefreshResponse(
                            "토큰 갱신 실패: " + e.getMessage(),
                            false,
                            null,
                            null
                    ));
        }
    }

    @Operation(summary = "토큰 상태 조회", description = "현재 Redis에 저장된 토큰들의 존재 여부를 확인합니다.")
    @GetMapping("/status")
    public ResponseEntity<KisTokenStatusResponse> getTokenStatus() {
        try {
            Boolean accessTokenExists = redisTemplate.hasKey(ACCESS_TOKEN_KEY);
            Boolean approvalKeyExists = redisTemplate.hasKey(APPROVAL_KEY_KEY);
            
            return ResponseEntity.ok(KisTokenStatusResponse.of(accessTokenExists, approvalKeyExists));
        } catch (Exception e) {
            log.error("토큰 상태 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(new KisTokenStatusResponse(false, false, "토큰 상태 조회 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "Access Token 삭제", description = "Redis에서 Access Token을 삭제합니다.")
    @DeleteMapping("/access-token")
    public ResponseEntity<Object> deleteAccessToken() {
        try {
            Boolean deleted = redisTemplate.delete(ACCESS_TOKEN_KEY);
            return ResponseEntity.ok(new TokenDeleteResponse(
                    "ACCESS_TOKEN",
                    deleted,
                    deleted ? "Access Token 삭제 성공" : "Access Token이 존재하지 않음"
            ));
        } catch (Exception e) {
            log.error("Access Token 삭제 실패", e);
            return ResponseEntity.internalServerError()
                    .body(new TokenDeleteResponse("ACCESS_TOKEN", false, "삭제 실패: " + e.getMessage()));
        }
    }

    @Operation(summary = "Approval Key 삭제", description = "Redis에서 Approval Key를 삭제합니다.")
    @DeleteMapping("/approval-key")
    public ResponseEntity<Object> deleteApprovalKey() {
        try {
            Boolean deleted = redisTemplate.delete(APPROVAL_KEY_KEY);
            return ResponseEntity.ok(new TokenDeleteResponse(
                    "APPROVAL_KEY",
                    deleted,
                    deleted ? "Approval Key 삭제 성공" : "Approval Key가 존재하지 않음"
            ));
        } catch (Exception e) {
            log.error("Approval Key 삭제 실패", e);
            return ResponseEntity.internalServerError()
                    .body(new TokenDeleteResponse("APPROVAL_KEY", false, "삭제 실패: " + e.getMessage()));
        }
    }

    // 내부 DTO 클래스들
    private record AllTokensRefreshResponse(
            String message,
            Boolean success,
            KisTokenRefreshResponse accessToken,
            KisTokenRefreshResponse approvalKey
    ) {}

    private record TokenDeleteResponse(
            String tokenType,
            Boolean deleted,
            String message
    ) {}
}

