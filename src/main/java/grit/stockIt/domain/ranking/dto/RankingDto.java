package grit.stockIt.domain.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "랭킹 정보")
public class RankingDto {

    @Schema(description = "순위", example = "1")
    private Integer rank;

    @Schema(description = "사용자 ID", example = "123")
    private Long memberId;

    @Schema(description = "닉네임", example = "투자왕김씨")
    private String nickname;

    @Schema(description = "잔액 (현금)", example = "15000000.00")
    private BigDecimal balance;

    @Schema(description = "수익률 (%) - 대회 계좌만 해당, Main 계좌는 null", example = "50.00")
    private BigDecimal returnRate;
}

