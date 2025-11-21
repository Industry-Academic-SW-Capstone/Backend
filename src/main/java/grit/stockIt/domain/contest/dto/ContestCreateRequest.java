package grit.stockIt.domain.contest.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "대회 생성 요청")
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ContestCreateRequest {
    @NotBlank(message = "대회명은 필수입니다")
    @Schema(description = "대회명", example = "2025 모의투자 대회")
    private String contestName;

    @NotNull(message = "시작일시는 필수입니다")
    @Schema(description = "대회 시작일시", example = "2025-01-01T09:00:00")
    private LocalDateTime startDate;

    @Schema(description = "대회 종료일시", example = "2025-01-31T18:00:00")
    private LocalDateTime endDate;

    @NotNull(message = "시드머니는 필수입니다")
    @Schema(description = "초기 시드머니 (원)", example = "10000000")
    private Long seedMoney;

    @NotNull(message = "수수료율은 필수입니다")
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

    @Schema(description = "대회 설명(노트)", example = "대회 규정 및 안내 등 상세 설명")
    private String contestNote;
}