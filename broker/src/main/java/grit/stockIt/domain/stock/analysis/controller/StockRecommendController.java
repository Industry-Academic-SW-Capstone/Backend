package grit.stockIt.domain.stock.analysis.controller;

import grit.stockIt.domain.stock.analysis.dto.RecommendResponse;
import grit.stockIt.domain.stock.analysis.service.StockRecommendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/stocks")
@Tag(name = "stocks", description = "주식 관련 API")
@RequiredArgsConstructor
public class StockRecommendController {

    private final StockRecommendService stockRecommendService;

    @Operation(
            summary = "종목 추천",
            description = "사용자의 보유 종목을 기반으로 AI가 투자 성향에 맞는 종목을 추천합니다."
    )
    @GetMapping("/recommend")
    public Mono<RecommendResponse> recommend(
            @Parameter(description = "계좌 ID", required = true)
            @RequestParam Long accountId,
            @Parameter(description = "페르소나 (예: '워렌 버핏'). 미입력 시 보유 종목 스타일 기반 추천")
            @RequestParam(required = false) String persona,
            @Parameter(description = "추천 종목 수 (기본값: 10, 최대: 50)")
            @RequestParam(defaultValue = "10") int topN,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("종목 추천 요청: accountId={}, persona={}, topN={}", accountId, persona, topN);
        return stockRecommendService.recommend(accountId, userDetails.getUsername(), persona, topN);
    }
}
