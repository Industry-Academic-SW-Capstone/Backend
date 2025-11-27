package grit.stockIt.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import grit.stockIt.domain.title.entity.Title;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
@Schema(description = "대표 칭호 응답 DTO")
@JsonInclude(JsonInclude.Include.ALWAYS) // [핵심] 값이 null이어도 필드를 JSON에 포함시킴
public class RepresentativeTitleResponse {
    @Schema(description = "칭호 ID", example = "103")
    private Long titleId;

    @Schema(description = "칭호 이름", example = "주식의 신")
    private String name;

    @Schema(description = "칭호 설명", example = "Legend 티어를 달성한 자")
    private String description;

    // Title 엔티티로부터 DTO 생성
    public static RepresentativeTitleResponse from(Title title) {
        // [수정] title이 없으면 null을 반환하는 게 아니라, 필드가 null인 객체를 반환
        if (title == null) {
            return RepresentativeTitleResponse.builder()
                    .titleId(null)
                    .name(null)
                    .description(null)
                    .build();
        }
        return RepresentativeTitleResponse.builder()
                .titleId(title.getId())
                .name(title.getName())
                .description(title.getDescription())
                .build();
    }
}