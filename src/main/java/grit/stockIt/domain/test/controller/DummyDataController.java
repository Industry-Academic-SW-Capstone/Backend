package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.test.dto.DummyDataResponse;
import grit.stockIt.domain.test.service.DummyDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 더미 데이터 생성 컨트롤러
 * - 성능 테스트를 위한 더미 데이터 생성
 * - 테스트 완료 후 삭제 예정
 */
@Slf4j
@RestController
@RequestMapping("/api/test/dummy")
@RequiredArgsConstructor
@Tag(name = "Dummy Data", description = "더미 데이터 생성 API (테스트용)")
public class DummyDataController {

    private final DummyDataService dummyDataService;

    /**
     * 더미 회원 및 Main 계좌 생성
     * 
     * @param memberCount 생성할 회원 수 (기본: 100명)
     * @return DummyDataResponse
     */
    @PostMapping("/generate")
    @Operation(
            summary = "더미 데이터 생성",
            description = "성능 테스트를 위한 더미 회원 및 Main 계좌를 생성합니다. " +
                    "각 회원은 랜덤한 잔액(10만~1000만원)을 가진 Main 계좌를 소유합니다."
    )
    public ResponseEntity<DummyDataResponse> generateDummyData(
            @Parameter(description = "생성할 회원 수 (1~1000)", example = "100")
            @RequestParam(defaultValue = "100") int memberCount
    ) {
        log.info("🚀 [더미 데이터 생성 API 호출] 회원 수: {}", memberCount);

        if (memberCount < 1 || memberCount > 1000) {
            throw new IllegalArgumentException("회원 수는 1~1000 사이여야 합니다. (현재: " + memberCount + ")");
        }

        DummyDataResponse response = dummyDataService.generateDummyData(memberCount);
        
        log.info("✅ [더미 데이터 생성 완료] 회원: {}명, Main 계좌: {}개, 소요 시간: {}ms",
                response.getMemberCount(), response.getAccountCount(), response.getElapsedTimeMs());

        return ResponseEntity.ok(response);
    }

    /**
     * 모든 더미 데이터 삭제
     */
    @DeleteMapping("/clear")
    @Operation(
            summary = "더미 데이터 삭제",
            description = "생성된 모든 더미 데이터를 삭제합니다. (test_user_ 로 시작하는 회원 및 계좌)"
    )
    public ResponseEntity<String> clearDummyData() {
        log.info("🗑️ [더미 데이터 삭제 API 호출]");
        
        int deletedCount = dummyDataService.clearDummyData();
        
        log.info("✅ [더미 데이터 삭제 완료] 삭제된 회원 수: {}", deletedCount);
        
        return ResponseEntity.ok(String.format("✅ 더미 데이터 삭제 완료! (회원 %d명 삭제)", deletedCount));
    }

    /**
     * 현재 더미 데이터 통계 조회
     */
    @GetMapping("/stats")
    @Operation(
            summary = "더미 데이터 통계",
            description = "현재 생성된 더미 데이터의 통계를 조회합니다."
    )
    public ResponseEntity<DummyDataResponse> getDummyDataStats() {
        log.info("📊 [더미 데이터 통계 조회]");
        
        DummyDataResponse stats = dummyDataService.getDummyDataStats();
        
        return ResponseEntity.ok(stats);
    }
}

