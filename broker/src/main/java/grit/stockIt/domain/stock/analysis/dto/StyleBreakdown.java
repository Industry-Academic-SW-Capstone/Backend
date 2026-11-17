package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StyleBreakdown(
    @JsonProperty("style_tag") String styleTag,
    @JsonProperty("percentage") Double percentage
) {}

