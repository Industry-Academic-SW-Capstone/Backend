package grit.stockIt.domain.stock.analysis.controller;

import grit.stockIt.domain.stock.analysis.dto.ReportStreamRequest;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import grit.stockIt.domain.stock.analysis.service.StockAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// 종목분석 컨트롤러
@Slf4j
@RestController
@RequestMapping("/api/stocks")
@Tag(name = "stock-analysis", description = "종목분석 API")
@RequiredArgsConstructor
public class StockAnalysisController {

    private final StockAnalysisService stockAnalysisService;

    @Operation(summary = "종목분석 (레거시)", description = "score + report를 한번에 반환합니다. 신규는 /score, /report/stream 사용 권장.")
    @PostMapping("/{stockCode}/analyze")
    public Mono<StockAnalysisResponse> analyzeStock(
            @PathVariable String stockCode,
            @AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails != null ? userDetails.getUsername() : null;
        return stockAnalysisService.analyzeStock(stockCode, email);
    }

    @Operation(summary = "종목 스코어링", description = "KMeans 스타일 태그 + 멀티팩터 점수만 반환 (< 1초). report/stream과 함께 사용.")
    @PostMapping("/{stockCode}/score")
    public Mono<StockAnalysisResponse> scoreStock(
            @PathVariable String stockCode,
            @AuthenticationPrincipal UserDetails userDetails) {
        String email = userDetails != null ? userDetails.getUsername() : null;
        return stockAnalysisService.scoreStock(stockCode, email);
    }

    @Operation(summary = "AI 투자 리포트 스트리밍", description = "뉴스 RAG + Gemini 리포트를 SSE로 스트리밍. 이벤트: news_ready → token(반복) → done")
    @PostMapping(value = "/{stockCode}/report/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<DataBuffer>> streamReport(
            @PathVariable String stockCode,
            @RequestBody ReportStreamRequest request) {
        log.info("리포트 스트리밍 요청: stockCode={}", stockCode);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(stockAnalysisService.streamReport(request));
    }
}

