package grit.stockIt.domain.ranking.controller;

import grit.stockIt.domain.ranking.dto.PerformanceResult;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import grit.stockIt.domain.ranking.service.PerformanceTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 성능 비교 테스트 전용 컨트롤러
 * - 캐시 O vs 캐시 X 성능 비교
 * - 테스트 완료 후 이 파일 전체 삭제 예정
 */
@Slf4j
@RestController
@RequestMapping("/api/rankings/performance-test")
@RequiredArgsConstructor
@Tag(name = "Performance Test", description = "랭킹 성능 비교 테스트 API (테스트용 - 나중에 삭제 예정)")
public class PerformanceTestController {

    private final PerformanceTestService performanceTestService;

    // ==================== 캐시 없는 랭킹 조회 (테스트용) ====================

    /**
     * Main 계좌 랭킹 조회 (캐시 없음)
     * - 매번 DB 조회
     * - 성능 비교 테스트용
     */
    @GetMapping("/main/no-cache")
    @Operation(
            summary = "Main 계좌 랭킹 조회 (캐시 없음)",
            description = "매번 DB를 조회하여 랭킹을 반환합니다. 성능 비교 테스트용입니다."
    )
    public ResponseEntity<RankingResponse> getMainRankingsNoCache() {
        log.info("🔴 [API 호출] Main 계좌 랭킹 (캐시 없음)");
        RankingResponse response = performanceTestService.getMainRankingsNoCache();
        return ResponseEntity.ok(response);
    }

    /**
     * 대회 계좌 랭킹 조회 (캐시 없음)
     */
    @GetMapping("/contest/{contestId}/no-cache")
    @Operation(
            summary = "대회 계좌 랭킹 조회 (캐시 없음)",
            description = "매번 DB를 조회하여 대회 랭킹을 반환합니다."
    )
    public ResponseEntity<RankingResponse> getContestRankingsNoCache(
            @Parameter(description = "대회 ID", example = "1")
            @PathVariable Long contestId,
            
            @Parameter(description = "정렬 기준 (balance: 잔액순, returnRate: 수익률순)", example = "balance")
            @RequestParam(defaultValue = "balance") String sortBy
    ) {
        log.info("🔴 [API 호출] 대회 [{}] 랭킹 (캐시 없음, sortBy: {})", contestId, sortBy);
        RankingResponse response = performanceTestService.getContestRankingsNoCache(contestId, sortBy);
        return ResponseEntity.ok(response);
    }

    // ==================== 성능 비교 테스트 ====================

    /**
     * Main 계좌 랭킹 성능 비교 (캐시 O vs 캐시 X)
     * - requestCount만큼 반복 요청하여 성능 측정
     *
     * 사용 예시:
     * GET /api/rankings/performance-test/main/compare?requestCount=100
     */
    @GetMapping("/main/compare")
    @Operation(
            summary = "Main 계좌 랭킹 성능 비교",
            description = "캐시 O vs 캐시 X 성능을 비교합니다. requestCount만큼 반복 요청하여 평균 응답 시간, DB 쿼리 횟수 등을 측정합니다."
    )
    public ResponseEntity<PerformanceResult> compareMainRankingPerformance(
            @Parameter(description = "요청 횟수 (예: 100명 동시 요청 시뮬레이션)", example = "100")
            @RequestParam(defaultValue = "100") int requestCount
    ) {
        log.info("🚀 [성능 비교 API 호출] Main 계좌 - {} 회 요청", requestCount);
        
        if (requestCount < 1 || requestCount > 1000) {
            throw new IllegalArgumentException("요청 횟수는 1~1000 사이여야 합니다. (현재: " + requestCount + ")");
        }
        
        PerformanceResult result = performanceTestService.compareMainRankingPerformance(requestCount);
        return ResponseEntity.ok(result);
    }

    /**
     * 대회 계좌 랭킹 성능 비교
     *
     * 사용 예시:
     * GET /api/rankings/performance-test/contest/1/compare?sortBy=balance&requestCount=100
     */
    @GetMapping("/contest/{contestId}/compare")
    @Operation(
            summary = "대회 계좌 랭킹 성능 비교",
            description = "캐시 O vs 캐시 X 성능을 비교합니다."
    )
    public ResponseEntity<PerformanceResult> compareContestRankingPerformance(
            @Parameter(description = "대회 ID", example = "1")
            @PathVariable Long contestId,
            
            @Parameter(description = "정렬 기준", example = "balance")
            @RequestParam(defaultValue = "balance") String sortBy,
            
            @Parameter(description = "요청 횟수", example = "100")
            @RequestParam(defaultValue = "100") int requestCount
    ) {
        log.info("🚀 [성능 비교 API 호출] 대회 [{}] - {} 회 요청 (sortBy: {})", contestId, requestCount, sortBy);
        
        if (requestCount < 1 || requestCount > 1000) {
            throw new IllegalArgumentException("요청 횟수는 1~1000 사이여야 합니다.");
        }
        
        PerformanceResult result = performanceTestService.compareContestRankingPerformance(contestId, sortBy, requestCount);
        return ResponseEntity.ok(result);
    }

    // ==================== 간단한 테스트용 엔드포인트 ====================

    /**
     * 성능 테스트 상태 확인
     */
    @GetMapping("/health")
    @Operation(
            summary = "성능 테스트 API 상태 확인",
            description = "성능 테스트 API가 정상 작동하는지 확인합니다."
    )
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("✅ Performance Test API is ready!");
    }
}

