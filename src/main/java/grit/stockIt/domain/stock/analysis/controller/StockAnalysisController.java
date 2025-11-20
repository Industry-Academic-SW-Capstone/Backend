package grit.stockIt.domain.stock.analysis.controller;

import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import grit.stockIt.domain.stock.analysis.service.StockAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

// 종목분석 컨트롤러
@Slf4j
@RestController
@RequestMapping("/api/stocks")
@Tag(name = "stock-analysis", description = "종목분석 API")
@RequiredArgsConstructor
public class StockAnalysisController {

    private final StockAnalysisService stockAnalysisService;

    @Operation(summary = "종목분석", description = "종목에 대한 AI 분석을 수행합니다.")
    @PostMapping("/{stockCode}/analyze")
    public Mono<StockAnalysisResponse> analyzeStock(@PathVariable String stockCode) {
        return stockAnalysisService.analyzeStock(stockCode);
    }
}

