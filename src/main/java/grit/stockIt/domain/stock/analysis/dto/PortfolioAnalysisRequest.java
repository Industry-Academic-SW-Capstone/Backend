package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PortfolioAnalysisRequest(
    @JsonProperty("stocks") List<PortfolioStockDto> stocks
) {}

