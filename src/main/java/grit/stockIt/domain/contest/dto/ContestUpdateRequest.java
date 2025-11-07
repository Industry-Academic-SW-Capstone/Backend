package grit.stockIt.domain.contest.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@Builder
@Schema(description = "대회 수정 요청")
public class ContestUpdateRequest {

    @JsonCreator
    public ContestUpdateRequest(
            @JsonProperty("contestName") String contestName,
            @JsonProperty("startDate") LocalDateTime startDate,
            @JsonProperty("endDate") LocalDateTime endDate,
            @JsonProperty("seedMoney") Long seedMoney,
            @JsonProperty("commissionRate") BigDecimal commissionRate,
            @JsonProperty("minMarketCap") Long minMarketCap,
            @JsonProperty("maxMarketCap") Long maxMarketCap,
            @JsonProperty("dailyTradeLimit") Integer dailyTradeLimit,
            @JsonProperty("maxHoldingsCount") Integer maxHoldingsCount,
            @JsonProperty("buyCooldownMinutes") Integer buyCooldownMinutes,
            @JsonProperty("sellCooldownMinutes") Integer sellCooldownMinutes) {
        this.contestName = contestName;
        this.startDate = startDate;
        this.endDate = endDate;
        this.seedMoney = seedMoney;
        this.commissionRate = commissionRate;
        this.minMarketCap = minMarketCap;
        this.maxMarketCap = maxMarketCap;
        this.dailyTradeLimit = dailyTradeLimit;
        this.maxHoldingsCount = maxHoldingsCount;
        this.buyCooldownMinutes = buyCooldownMinutes;
        this.sellCooldownMinutes = sellCooldownMinutes;
    }

    @Schema(description = "대회명", example = "2025 모의투자 대회")
    private String contestName;

    @Schema(description = "대회 시작일시", example = "2025-01-01T09:00:00")
    private LocalDateTime startDate;

    @Schema(description = "대회 종료일시", example = "2025-01-31T18:00:00")
    private LocalDateTime endDate;

    @Schema(description = "초기 시드머니 (원)", example = "10000000")
    private Long seedMoney;

    @Schema(description = "거래 수수료율 (0.0000 ~ 1.0000)", example = "0.0015")
    private BigDecimal commissionRate;

    @Schema(description = "최소 시가총액 제한 (원)", example = "1000000000")
    private Long minMarketCap;

    @Schema(description = "최대 시가총액 제한 (원)", example = "100000000000")
    private Long maxMarketCap;

    @Schema(description = "일일 거래 횟수 제한", example = "10")
    private Integer dailyTradeLimit;

    @Schema(description = "최대 보유 종목 수", example = "5")
    private Integer maxHoldingsCount;

    @Schema(description = "매수 후 쿨다운 시간 (분)", example = "5")
    private Integer buyCooldownMinutes;

    @Schema(description = "매도 후 쿨다운 시간 (분)", example = "5")
    private Integer sellCooldownMinutes;
}
