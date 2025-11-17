package grit.stockIt.domain.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "랭킹 응답")
public class RankingResponse {

    @Schema(description = "대회 ID (null인 경우 Main 계좌)", example = "1")
    private Long contestId;

    @Schema(description = "대회명", example = "2025 모의투자 대회")
    private String contestName;

    @Schema(description = "정렬 기준 (balance: 잔액순, returnRate: 수익률순)", example = "balance")
    private String sortBy;

    @Schema(description = "랭킹 목록")
    private List<RankingDto> rankings;

    @Schema(description = "전체 참가자 수", example = "850")
    private Long totalParticipants;

    @Schema(description = "마지막 갱신 시간", example = "2025-11-17T14:23:00")
    private LocalDateTime lastUpdated;
}

