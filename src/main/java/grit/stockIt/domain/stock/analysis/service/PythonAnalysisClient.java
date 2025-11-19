package grit.stockIt.domain.stock.analysis.service;

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
        log.info("Python 서버에 종목분석 요청: stockCode={}", request.stockCode());

        return webClient.post()
                .uri(pythonServerUrl + "/stock/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(StockAnalysisResponse.class)
                .doOnSuccess(response -> log.info("Python 서버 분석 완료: stockCode={}", request.stockCode()))
                .doOnError(e -> log.error("Python 서버 분석 실패: stockCode={}", request.stockCode(), e));
    }
}

