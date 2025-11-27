package grit.stockIt.domain.stock.analysis.controller;

import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisResponse;
import grit.stockIt.domain.stock.analysis.service.PortfolioAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 포트폴리오 분석 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/portfolio")
@Tag(name = "portfolio-analysis", description = "포트폴리오 분석 API")
@RequiredArgsConstructor
public class PortfolioAnalysisController {

    private final PortfolioAnalysisService portfolioAnalysisService;

    @Operation(
            summary = "포트폴리오 분석",
            description = "사용자의 보유 종목을 분석하여 투자 스타일과 페르소나를 매칭합니다."
    )
    @GetMapping("/analyze")
    public Mono<PortfolioAnalysisResponse> analyzePortfolio(
            @Parameter(description = "계좌 ID", required = true)
            @RequestParam Long accountId, @AuthenticationPrincipal UserDetails userDetails
    ) {
        log.info("포트폴리오 분석 요청: accountId={}", accountId);
        return portfolioAnalysisService.analyzePortfolio(accountId, userDetails.getUsername());
    }
}

