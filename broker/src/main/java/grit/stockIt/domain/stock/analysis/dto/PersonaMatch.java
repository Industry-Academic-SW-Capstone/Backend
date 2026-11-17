package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PersonaMatch(
    @JsonProperty("name") String name,
    @JsonProperty("percentage") Double percentage,
    @JsonProperty("philosophy") String philosophy
) {}

