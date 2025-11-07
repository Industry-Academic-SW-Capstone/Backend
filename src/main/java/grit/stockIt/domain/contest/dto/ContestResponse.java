package grit.stockIt.domain.contest.dto;

import grit.stockIt.domain.contest.entity.Contest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "대회 응답")
public class ContestResponse {

    @Schema(description = "대회 ID", example = "1")
    private Long contestId;

    @Schema(description = "대회명", example = "2025 신년 모의투자 대회")
    private String contestName;

    @Schema(description = "기본 대회 여부", example = "false")
    private Boolean isDefault;

    @Schema(description = "방장(관리자) 회원 ID", example = "123")
    private Long managerMemberId;

    @Schema(description = "대회 시작일시", example = "2025-01-01T09:00:00")
    private LocalDateTime startDate;

    @Schema(description = "대회 종료일시", example = "2025-01-31T18:00:00")
    private LocalDateTime endDate;

    @Schema(description = "초기 시드머니 (원)", example = "10000000")
    private Long seedMoney;

    @Schema(description = "거래 수수료율", example = "0.0015")
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

    @Schema(description = "생성일시", example = "2025-01-01T00:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "수정일시", example = "2025-01-01T00:00:00")
    private LocalDateTime updatedAt;

    public static ContestResponse from(Contest contest) {
        return ContestResponse.builder()
                .contestId(contest.getContestId())
                .contestName(contest.getContestName())
                .isDefault(contest.getIsDefault())
                .managerMemberId(contest.getManagerMemberId())
                .startDate(contest.getStartDate())
                .endDate(contest.getEndDate())
                .seedMoney(contest.getSeedMoney())
                .commissionRate(contest.getCommissionRate())
                .minMarketCap(contest.getMinMarketCap())
                .maxMarketCap(contest.getMaxMarketCap())
                .dailyTradeLimit(contest.getDailyTradeLimit())
                .maxHoldingsCount(contest.getMaxHoldingsCount())
                .buyCooldownMinutes(contest.getBuyCooldownMinutes())
                .sellCooldownMinutes(contest.getSellCooldownMinutes())
                .createdAt(contest.getCreatedAt())
                .updatedAt(contest.getUpdatedAt())
                .build();
    }
}
