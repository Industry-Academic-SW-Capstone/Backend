package grit.stockIt.domain.ranking.controller;

import grit.stockIt.domain.ranking.dto.MemberPortfolioResponse;
import grit.stockIt.domain.ranking.dto.MyRankDto;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import grit.stockIt.domain.ranking.service.RankingService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 랭킹 API 컨트롤러
 * - Main 계좌 랭킹 조회
 * - 대회 계좌 랭킹 조회 (잔액순/수익률순)
 * - 내 랭킹 조회
 */
@Slf4j
@RestController
@RequestMapping("/api/rankings")
@RequiredArgsConstructor
@Tag(name = "Ranking", description = "랭킹 API")
public class RankingController {

    private final RankingService rankingService;

    // ==================== Main 계좌 랭킹 ====================

    /**
     * Main 계좌 전체 랭킹 조회 (잔액순)
     * - 1분마다 자동 갱신되는 캐시 데이터 반환
     * - 매우 빠른 응답 속도 (캐시 사용)
     *
     * GET /api/rankings/main
     */
    @GetMapping("/main")
    @Operation(
            summary = "Main 계좌 랭킹 조회",
            description = "Main 계좌 전체 랭킹을 잔액 순으로 조회합니다. 캐시를 사용하여 빠르게 응답합니다."
    )
    public ResponseEntity<RankingResponse> getMainRankings() {
        log.info("[API 호출] Main 계좌 랭킹 조회");
        RankingResponse response = rankingService.getMainRankings();
        return ResponseEntity.ok(response);
    }

    // ==================== 대회 계좌 랭킹 ====================

    /**
     * 대회 계좌 전체 랭킹 조회
     * - sortBy 파라미터로 정렬 기준 선택
     *   - totalAssets: 총자산순 (기본값)
     *   - returnRate: 수익률순
     *
     * GET /api/rankings/contest/{contestId}?sortBy=totalAssets
     * GET /api/rankings/contest/{contestId}?sortBy=returnRate
     */
    @GetMapping("/contest/{contestId}")
    @Operation(
            summary = "대회 계좌 랭킹 조회",
            description = "특정 대회의 랭킹을 조회합니다. sortBy 파라미터로 총자산순/수익률순 선택 가능합니다."
    )
    public ResponseEntity<RankingResponse> getContestRankings(
            @Parameter(description = "대회 ID", example = "1", required = true)
            @PathVariable("contestId") Long contestId,

            @Parameter(description = "정렬 기준 (totalAssets: 총자산순, returnRate: 수익률순)", example = "totalAssets")
            @RequestParam(name = "sortBy", defaultValue = "totalAssets") String sortBy
    ) {
        log.info("[API 호출] 대회 [{}] 랭킹 조회 (sortBy: {})", contestId, sortBy);

        // sortBy 유효성 검사
        if (!sortBy.equalsIgnoreCase("totalAssets") && !sortBy.equalsIgnoreCase("returnRate")) {
            throw new IllegalArgumentException("sortBy는 'totalAssets' 또는 'returnRate'여야 합니다. (현재: " + sortBy + ")");
        }

        RankingResponse response = rankingService.getContestRankings(contestId, sortBy);
        return ResponseEntity.ok(response);
    }

    // ==================== 내 랭킹 조회 ====================

    /**
     * 내 랭킹 정보 조회
     * - contestId가 없으면 Main 계좌 랭킹
     * - contestId가 있으면 해당 대회 랭킹 (잔액 + 수익률)
     *
     * GET /api/rankings/me?memberId=42
     * GET /api/rankings/me?memberId=42&contestId=1
     */
    @GetMapping("/me")
    @Operation(
            summary = "내 랭킹 조회",
            description = "내 랭킹 정보를 조회합니다. contestId가 없으면 Main 계좌, 있으면 해당 대회의 랭킹을 반환합니다."
    )
    public ResponseEntity<MyRankDto> getMyRank(
            @Parameter(description = "회원 ID", example = "42", required = true)
            @RequestParam(name = "memberId") Long memberId,

            @Parameter(description = "대회 ID (없으면 Main 계좌)", example = "1")
            @RequestParam(name = "contestId", required = false) Long contestId
    ) {
        log.info("🔍 [API 호출] 내 랭킹 조회 (memberId: {}, contestId: {})", memberId, contestId);
        MyRankDto response = rankingService.getMyRank(memberId, contestId);
        return ResponseEntity.ok(response);
    }

    // ==================== 대회 참가자 포트폴리오 조회 ====================

    /**
     * 대회 참가자의 포트폴리오 조회
     * - 같은 대회에 참가 중인 사용자만 조회 가능
     * - 랭킹 화면에서 다른 참가자 이름 클릭 시 호출
     *
     * GET /api/rankings/contest/{contestId}/members/{memberId}/portfolio
     */
    @GetMapping("/contest/{contestId}/members/{memberId}/portfolio")
    @Operation(
            summary = "대회 참가자 포트폴리오 조회",
            description = "같은 대회에 참가 중인 다른 참가자의 포트폴리오를 조회합니다. 종목별 비중, 손익 정보를 포함합니다."
    )
    public ResponseEntity<MemberPortfolioResponse> getMemberPortfolio(
            @Parameter(description = "대회 ID", example = "1", required = true)
            @PathVariable("contestId") Long contestId,

            @Parameter(description = "조회 대상 회원 ID", example = "42", required = true)
            @PathVariable("memberId") Long memberId
    ) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String requesterEmail = authentication.getName();

        log.info("[API 호출] 대회 참가자 포트폴리오 조회 (contestId: {}, memberId: {}, requester: {})",
                contestId, memberId, requesterEmail);

        MemberPortfolioResponse response = rankingService.getMemberPortfolio(contestId, memberId, requesterEmail);
        return ResponseEntity.ok(response);
    }

    // ==================== 예외 처리 ====================

    /**
     * IllegalArgumentException 핸들러
     * - 잘못된 파라미터 입력 시 400 Bad Request 반환
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[예외] {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}

