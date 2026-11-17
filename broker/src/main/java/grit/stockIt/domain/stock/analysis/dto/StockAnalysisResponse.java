package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

// Python 서버에서 받은 종목분석 응답 DTO
public record StockAnalysisResponse(
    @JsonProperty("stock_code") String stockCode,
    @JsonProperty("stock_name") String stockName,
    @JsonProperty("final_style_tag") String finalStyleTag,
    @JsonProperty("style_description") String styleDescription,
    @JsonProperty("analyzable") Boolean analyzable,
    @JsonProperty("reason") String reason,
    @JsonProperty("scores") ScoreDetail scores,       // Step 3: 멀티팩터 스코어
    @JsonProperty("report") String report              // Step 4: AI 투자 분석 리포트
) {
    // 기존 6인자 생성자 호환 (PythonAnalysisClient fallback용)
    public StockAnalysisResponse(String stockCode, String stockName, String finalStyleTag,
                                  String styleDescription, Boolean analyzable, String reason) {
        this(stockCode, stockName, finalStyleTag, styleDescription, analyzable, reason, null, null);
    }

    // 스코어링 상세 결과
    public record ScoreDetail(
        @JsonProperty("growth_score") Double growthScore,
        @JsonProperty("stability_score") Double stabilityScore,
        @JsonProperty("similarity_score") Double similarityScore,
        @JsonProperty("composite_score") Double compositeScore
    ) {}
}

