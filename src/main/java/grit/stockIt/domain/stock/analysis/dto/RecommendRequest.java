package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

// Python 서버 /stock/recommend 요청 DTO
public record RecommendRequest(
    @JsonProperty("stocks") List<RecommendStockDto> stocks,
    @JsonProperty("persona") String persona,
    @JsonProperty("top_n") int topN
) {}
