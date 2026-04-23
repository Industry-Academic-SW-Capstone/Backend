package grit.stockIt.domain.ranking.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "대회 참가자 포트폴리오 조회 응답")
public record MemberPortfolioResponse(

        @Schema(description = "회원 ID", example = "42")
        Long memberId,

        @Schema(description = "닉네임", example = "투자왕김씨")
        String nickname,

        @Schema(description = "프로필 이미지 URL")
        String profileImage,

        @Schema(description = "대표 칭호")
        String representativeTitle,

        @Schema(description = "총 자산", example = "12000000.00")
        BigDecimal totalAssets,

        @Schema(description = "보유 현금", example = "3000000.00")
        BigDecimal cash,

        @Schema(description = "현금 비중 (%)", example = "25.00")
        BigDecimal cashPercent,

        @Schema(description = "주식 평가액 합계", example = "9000000.00")
        BigDecimal stockValue,

        @Schema(description = "수익률 (%)", example = "20.00")
        BigDecimal returnRate,

        @Schema(description = "보유 종목 목록")
        List<PortfolioItem> holdings
) {
    @Schema(description = "포트폴리오 종목 항목")
    public record PortfolioItem(

            @Schema(description = "종목 코드", example = "005930")
            String stockCode,

            @Schema(description = "종목명", example = "삼성전자")
            String stockName,

            @Schema(description = "보유 수량", example = "10")
            int quantity,

            @Schema(description = "1주 평균 매입가", example = "70000.00")
            BigDecimal averagePrice,

            @Schema(description = "현재가", example = "72000.00")
            BigDecimal currentPrice,

            @Schema(description = "종목 평가액 (quantity × currentPrice)", example = "720000.00")
            BigDecimal totalValue,

            @Schema(description = "포트폴리오 내 비중 (%)", example = "8.00")
            BigDecimal percent,

            @Schema(description = "1주당 손익", example = "2000.00")
            BigDecimal profitLossPerShare,

            @Schema(description = "총 손익", example = "20000.00")
            BigDecimal profitLossTotal,

            @Schema(description = "수익률 (%)", example = "2.86")
            BigDecimal profitRate
    ) {}
}
