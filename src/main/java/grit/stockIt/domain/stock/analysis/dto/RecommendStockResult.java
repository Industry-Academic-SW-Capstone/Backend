package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// Python 서버 /stock/recommend 응답 내 개별 추천 종목
public record RecommendStockResult(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("stock_name") String stockName,
    @JsonProperty("style_tag") String styleTag,
    @JsonProperty("growth_score") Double growthScore,
    @JsonProperty("stability_score") Double stabilityScore,
    @JsonProperty("similarity_score") Double similarityScore,
    @JsonProperty("composite_score") Double compositeScore
) {}
