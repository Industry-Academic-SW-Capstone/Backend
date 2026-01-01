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
import reactor.util.retry.Retry;

import java.time.Duration;

// Python 서버 클라이언트
@Slf4j
@Service
@RequiredArgsConstructor
public class PythonAnalysisClient {

    @Value("${python.analysis.url:http://localhost:8000}")
    private String pythonServerUrl;

    private final WebClient.Builder webClientBuilder;

    // Python 서버 전용 WebClient 생성 (baseUrl 없이 사용)
    private WebClient getPythonWebClient() {
        return webClientBuilder
                .baseUrl(pythonServerUrl)
                .build();
    }

    // Python 서버에 종목분석 요청
    public Mono<StockAnalysisResponse> analyze(StockAnalysisRequest request) {
        log.info("Python 서버 종목분석 요청: stockCode={}, url={}", request.stockCode(), pythonServerUrl);
        
        return getPythonWebClient()
                .post()
                .uri("/stock/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(StockAnalysisResponse.class)
                .doOnSuccess(response -> 
                    log.info("Python 서버 종목분석 성공: stockCode={}", request.stockCode())
                )
                .doOnError(error -> 
                    log.error("Python 서버 종목분석 실패: stockCode={}, url={}", request.stockCode(), pythonServerUrl, error)
                )
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> {
                            // 연결 에러만 재시도
                            String message = throwable.getMessage();
                            return message != null && (
                                message.contains("Connection refused") ||
                                message.contains("timeout") ||
                                message.contains("Connection reset")
                            );
                        })
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Python 서버 재시도: {}번째 시도", retrySignal.totalRetries() + 1)
                        )
                )
                .onErrorResume(throwable -> {
                    log.error("Python 서버 호출 최종 실패: stockCode={}, error={}", request.stockCode(), throwable.getMessage());
                    // AI 서버 오류 시 기본 응답 반환 (분석 불가로 처리)
                    return Mono.just(new StockAnalysisResponse(
                        request.stockCode(),
                        null,
                        null,
                        null,
                        false,
                        "AI 서버 오류로 분석에 실패했습니다."
                    ));
                });
    }

    // Python 서버에 포트폴리오 분석 요청
    public Mono<PortfolioAnalysisResponse> analyzePortfolio(PortfolioAnalysisRequest request) {
        log.info("Python 서버 포트폴리오 분석 요청: {}개 종목, url={}", request.stocks().size(), pythonServerUrl);
        
        // 요청 데이터 확인 (디버깅용)
        request.stocks().forEach(stock -> 
            log.debug("종목 데이터: stock_code={}, stock_name={}, investment_amount={}", 
                stock.stockCode(), stock.stockName(), stock.investmentAmount())
        );

        return getPythonWebClient()
                .post()
                .uri("/portfolio/analyze")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PortfolioAnalysisResponse.class)
                .doOnSuccess(response -> 
                    log.info("Python 서버 포트폴리오 분석 완료: {}개 종목 분석됨", response.stockDetails().size())
                )
                .doOnError(error -> 
                    log.error("Python 서버 포트폴리오 분석 실패: url={}", pythonServerUrl, error)
                )
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> {
                            // 연결 에러만 재시도
                            String message = throwable.getMessage();
                            return message != null && (
                                message.contains("Connection refused") ||
                                message.contains("timeout") ||
                                message.contains("Connection reset")
                            );
                        })
                        .doBeforeRetry(retrySignal -> 
                            log.warn("Python 서버 재시도: {}번째 시도", retrySignal.totalRetries() + 1)
                        )
                )
                .onErrorMap(throwable -> {
                    log.error("Python 서버 호출 최종 실패: {}", throwable.getMessage());
                    return new RuntimeException("AI 서버 분석 실패: " + throwable.getMessage(), throwable);
                });
    }
}

