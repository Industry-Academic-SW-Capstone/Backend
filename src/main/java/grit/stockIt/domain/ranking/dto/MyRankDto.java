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
@Schema(description = "내 랭킹 정보")
public class MyRankDto {

    @Schema(description = "잔액 기준 내 순위", example = "42")
    private Long balanceRank;

    @Schema(description = "수익률 기준 내 순위 (대회만, Main은 null)", example = "35")
    private Long returnRateRank;

    @Schema(description = "전체 참가자 수", example = "150")
    private Long totalParticipants;

    @Schema(description = "내 잔액", example = "12500000.00")
    private BigDecimal myBalance;

    @Schema(description = "내 수익률 (%) - 대회만, Main은 null", example = "25.00")
    private BigDecimal myReturnRate;
}

