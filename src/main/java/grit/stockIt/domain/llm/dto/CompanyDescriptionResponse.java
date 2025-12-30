package grit.stockIt.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 기업 설명 응답 DTO
 */
@Schema(description = "기업 설명 응답")
public record CompanyDescriptionResponse(
        @JsonProperty("company_name")
        @Schema(description = "기업명", example = "삼성전자")
        String companyName,
        
        @JsonProperty("description")
        @Schema(description = "기업 설명 (두 문장 이내)", example = "삼성전자는 대한민국을 대표하는 세계적인 종합 전자 기업입니다. 메모리 반도체, 스마트폰, TV, 가전제품 등 광범위한 분야에서 글로벌 리더십을 가지고 있습니다.")
        String description,
        
        @JsonProperty("cached")
        @Schema(description = "캐시에서 조회했는지 여부", example = "false")
        boolean cached
) {
}

