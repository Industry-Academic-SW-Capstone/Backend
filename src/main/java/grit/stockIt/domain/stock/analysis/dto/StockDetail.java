package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StockDetail(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("stock_name") String stockName,
    @JsonProperty("style_tag") String styleTag,
    @JsonProperty("description") String description
) {}

