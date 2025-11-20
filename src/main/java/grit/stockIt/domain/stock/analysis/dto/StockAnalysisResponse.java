package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Python 서버에서 받은 종목분석 응답 DTO
public record StockAnalysisResponse(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("stock_name") String stockName,
    @JsonProperty("final_style_tag") String finalStyleTag,
    @JsonProperty("style_description") String styleDescription
) {}

