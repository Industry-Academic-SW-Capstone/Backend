package grit.stockIt.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 캐시 성능 비교 테스트 응답 DTO
 */
@Schema(description = "캐시 성능 비교 테스트 응답")
public record CachePerformanceResponse(
        @JsonProperty("company_name")
        @Schema(description = "기업명", example = "삼성전자")
        String companyName,
        
        @JsonProperty("without_cache")
        @Schema(description = "캐시 없이 조회한 경우")
        PerformanceResult withoutCache,
        
        @JsonProperty("with_cache")
        @Schema(description = "캐시로 조회한 경우")
        PerformanceResult withCache,
        
        @JsonProperty("time_saved")
        @Schema(description = "캐시로 인해 절약된 시간 (밀리초)", example = "29000")
        long timeSaved,
        
        @JsonProperty("time_saved_description")
        @Schema(description = "시간 절약 설명", example = "원래라면 똑같은걸 검색하면 이렇게 30초 걸리던걸 캐시 걸려서 1초 걸렸다. 29초를 단축할 수 있었다.")
        String timeSavedDescription
) {
    /**
     * 성능 측정 결과
     */
    @Schema(description = "성능 측정 결과")
    public record PerformanceResult(
            @JsonProperty("duration_ms")
            @Schema(description = "소요 시간 (밀리초)", example = "30000")
            long durationMs,
            
            @JsonProperty("description")
            @Schema(description = "기업 설명", example = "삼성전자는 대한민국을 대표하는 세계적인 종합 전자 기업입니다...")
            String description,
            
            @JsonProperty("cached")
            @Schema(description = "캐시에서 조회했는지 여부", example = "false")
            boolean cached
    ) {}
}

