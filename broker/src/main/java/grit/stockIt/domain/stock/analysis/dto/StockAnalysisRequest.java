package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Python 서버로 전송하는 종목분석 요청 DTO
public record StockAnalysisRequest(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("market_cap") Double marketCap,
    @JsonProperty("per") Double per,
    @JsonProperty("pbr") Double pbr,
    @JsonProperty("roe") Double roe,
    @JsonProperty("debt_ratio") Double debtRatio,
    @JsonProperty("dividend_yield") Double dividendYield
) {}

