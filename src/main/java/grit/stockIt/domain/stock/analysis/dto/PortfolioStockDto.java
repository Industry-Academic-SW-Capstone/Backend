package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Python 서버로 전송하는 포트폴리오 분석 요청 DTO
// stock_name 필드 추가: Python 서버가 DB에서 찾지 못할 경우를 대비
public record PortfolioStockDto(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("stock_name") String stockName,
    @JsonProperty("market_cap") Double marketCap,
    @JsonProperty("per") Double per,
    @JsonProperty("pbr") Double pbr,
    @JsonProperty("roe") Double roe,
    @JsonProperty("debt_ratio") Double debtRatio,
    @JsonProperty("dividend_yield") Double dividendYield,
    @JsonProperty("investment_amount") Double investmentAmount
) {}

