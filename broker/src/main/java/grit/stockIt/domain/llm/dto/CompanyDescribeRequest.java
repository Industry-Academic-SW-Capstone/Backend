package grit.stockIt.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * 기업 설명 요청 DTO
 */
@Schema(description = "기업 설명 요청")
public record CompanyDescribeRequest(
        @NotBlank(message = "기업명은 필수입니다")
        @JsonProperty("company_name")
        @Schema(description = "기업명", example = "삼성전자", required = true)
        String companyName
) {}

