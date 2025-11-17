package grit.stockIt.domain.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 성능 비교 결과 DTO
 * - 캐시 O vs 캐시 X 성능 비교 결과 저장
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "랭킹 성능 비교 결과")
public class PerformanceResult {

    @Schema(description = "테스트 요청 수", example = "100")
    private Integer requestCount;

    @Schema(description = "캐시 사용 시 평균 응답 시간 (ms)", example = "2.5")
    private Double cachedAvgTimeMs;

    @Schema(description = "캐시 미사용 시 평균 응답 시간 (ms)", example = "45.8")
    private Double noCacheAvgTimeMs;

    @Schema(description = "캐시 사용 시 최소 응답 시간 (ms)", example = "1.2")
    private Long cachedMinTimeMs;

    @Schema(description = "캐시 사용 시 최대 응답 시간 (ms)", example = "5.3")
    private Long cachedMaxTimeMs;

    @Schema(description = "캐시 미사용 시 최소 응답 시간 (ms)", example = "38.5")
    private Long noCacheMinTimeMs;

    @Schema(description = "캐시 미사용 시 최대 응답 시간 (ms)", example = "120.4")
    private Long noCacheMaxTimeMs;

    @Schema(description = "캐시 사용 시 총 소요 시간 (ms)", example = "250")
    private Long cachedTotalTimeMs;

    @Schema(description = "캐시 미사용 시 총 소요 시간 (ms)", example = "4580")
    private Long noCacheTotalTimeMs;

    @Schema(description = "DB 쿼리 실행 횟수 - 캐시 사용", example = "1")
    private Integer cachedDbQueryCount;

    @Schema(description = "DB 쿼리 실행 횟수 - 캐시 미사용", example = "100")
    private Integer noCacheDbQueryCount;

    @Schema(description = "승자", example = "캐시 사용 O")
    private String winner;

    @Schema(description = "성능 개선율 (%)", example = "94.5")
    private Double improvementPercent;

    @Schema(description = "속도 비교 (몇 배 빠른지)", example = "18.3")
    private Double speedupFactor;

    @Schema(description = "DB 부하 감소율 (%)", example = "99.0")
    private Double dbLoadReduction;

    @Schema(description = "결론 메시지", example = "캐시 사용 시 18.3배 빠르며, DB 쿼리는 99%감소했습니다.")
    private String conclusion;
}

