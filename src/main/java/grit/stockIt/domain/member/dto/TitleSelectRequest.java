package grit.stockIt.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonProperty; // 임포트 추가
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@Schema(description = "대표 칭호 변경 요청 DTO")
public class TitleSelectRequest {

    @Schema(description = "설정할 칭호 ID (null로 보낼 시 칭호 해제)", example = "103")
    @JsonProperty("titleId") // [수정] JSON 키를 "titleId"로 고정
    private Long titleId;
}