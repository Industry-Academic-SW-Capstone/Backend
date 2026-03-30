package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// /score 엔드포인트 요청 DTO (Optional — 없으면 기존처럼 유사도 50.0)
public record ScoreRequest(
    @JsonProperty("portfolio_stocks") List<PortfolioStockDto> portfolioStocks,
    @JsonProperty("persona") String persona
) {
    public record PortfolioStockDto(
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("market_cap") Double marketCap,
        @JsonProperty("per") Double per,
        @JsonProperty("pbr") Double pbr,
        @JsonProperty("roe") Double roe,
        @JsonProperty("debt_ratio") Double debtRatio,
        @JsonProperty("dividend_yield") Double dividendYield,
        @JsonProperty("investment_amount") Double investmentAmount
    ) {}
}
