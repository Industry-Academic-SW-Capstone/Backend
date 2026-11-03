package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record PortfolioAnalysisResponse(
    @JsonProperty("stock_details") List<StockDetail> stockDetails,
    @JsonProperty("summary") Map<String, Double> summary,
    @JsonProperty("style_breakdown") List<StyleBreakdown> styleBreakdown,
    @JsonProperty("persona_match") List<PersonaMatch> personaMatch
) {}

