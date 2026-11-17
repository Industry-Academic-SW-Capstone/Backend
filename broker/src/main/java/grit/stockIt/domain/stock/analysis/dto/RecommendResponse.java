package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// Python 서버 /stock/recommend 응답 DTO
public record RecommendResponse(
    @JsonProperty("persona") String persona,
    @JsonProperty("total_scored") int totalScored,
    @JsonProperty("recommendations") List<RecommendStockResult> recommendations
) {}
