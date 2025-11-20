package grit.stockIt.domain.stock.analysis.service;

import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisRequest;
import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisResponse;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisRequest;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

// Python 서버 클라이언트
@Slf4j
@Service
@RequiredArgsConstructor
public class PythonAnalysisClient {

    @Value("${python.analysis.url:http://localhost:8000}")
    private String pythonServerUrl;

    private final WebClient webClient;

    // Python 서버에 종목분석 요청
    public Mono<StockAnalysisResponse> analyze(StockAnalysisRequest request) {
        return webClient.post()
                .uri(pythonServerUrl + "/stock/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(StockAnalysisResponse.class);
    }

    // Python 서버에 포트폴리오 분석 요청
    public Mono<PortfolioAnalysisResponse> analyzePortfolio(PortfolioAnalysisRequest request) {
        log.info("포트폴리오 분석 요청: {}개 종목", request.stocks().size());
        
        // 요청 데이터 확인 (디버깅용)
        request.stocks().forEach(stock -> 
            log.debug("종목 데이터: stock_code={}, stock_name={}, investment_amount={}", 
                stock.stockCode(), stock.stockName(), stock.investmentAmount())
        );

        return webClient.post()
                .uri(pythonServerUrl + "/portfolio/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PortfolioAnalysisResponse.class)
                .doOnSuccess(response -> 
                    log.info("포트폴리오 분석 완료: {}개 종목 분석됨", response.stockDetails().size())
                )
                .onErrorResume(e -> {
                    log.error("포트폴리오 분석 API 호출 실패", e);
                    return Mono.error(new RuntimeException("AI 서버 분석 실패: " + e.getMessage(), e));
                });
    }
}

