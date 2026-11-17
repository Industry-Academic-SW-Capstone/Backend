package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Python 서버 /stock/recommend 요청용 개별 종목 DTO
public record RecommendStockDto(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("market_cap") Double marketCap,
    @JsonProperty("per") Double per,
    @JsonProperty("pbr") Double pbr,
    @JsonProperty("roe") Double roe,
    @JsonProperty("debt_ratio") Double debtRatio,
    @JsonProperty("dividend_yield") Double dividendYield,
    @JsonProperty("investment_amount") Double investmentAmount
) {}
