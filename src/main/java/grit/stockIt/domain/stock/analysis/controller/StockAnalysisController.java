package grit.stockIt.domain.stock.analysis.controller;

import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import grit.stockIt.domain.stock.analysis.service.StockAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

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
    public Mono<StockAnalysisResponse> analyzeStock(@PathVariable String stockCode, @AuthenticationPrincipal UserDetails userDetails) {
        // 비로그인 사용자 처리(선택사항)가 필요하다면 여기서 체크
        String email = userDetails != null ? userDetails.getUsername() : null;

        // 서비스로 email 전달
        return stockAnalysisService.analyzeStock(stockCode, email);
    }
}

