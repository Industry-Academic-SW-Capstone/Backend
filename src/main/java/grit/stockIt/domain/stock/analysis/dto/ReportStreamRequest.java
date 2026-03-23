package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Python 서버 /stock/report/stream 에 전달하는 요청 DTO
public record ReportStreamRequest(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("stock_name") String stockName,
    @JsonProperty("style_tag") String styleTag,
    @JsonProperty("growth_score") Double growthScore,
    @JsonProperty("stability_score") Double stabilityScore,
    @JsonProperty("composite_score") Double compositeScore
) {}
