package grit.stockIt.domain.stock.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisRequest;
import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisResponse;
import grit.stockIt.domain.stock.analysis.dto.RecommendRequest;
import grit.stockIt.domain.stock.analysis.dto.RecommendResponse;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisRequest;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
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

    // Python 응답 전용 ObjectMapper (Spring Boot의 SNAKE_CASE 설정 없이 @JsonProperty 기반으로만 처리)
    private static final ObjectMapper PYTHON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Value("${python.analysis.url:http://localhost:8000}")
    private String pythonServerUrl;

    private final WebClient.Builder webClientBuilder;

    @PostConstruct
    public void init() {
        log.info("Python AI 서버 URL 설정: {}", pythonServerUrl);
    }

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
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class).flatMap(errorBody -> {
                        log.error("Python HTTP 오류: stockCode={}, status={}, body={}",
                            request.stockCode(), response.statusCode(),
                            errorBody.length() > 300 ? errorBody.substring(0, 300) : errorBody);
                        return Mono.error(new RuntimeException("Python HTTP " + response.statusCode()));
                    })
                )
                .bodyToMono(String.class)
                .doOnNext(raw ->
                    log.info("Python 원시 응답 [{}]: {}", request.stockCode(),
                        raw.length() > 500 ? raw.substring(0, 500) + "..." : raw)
                )
                .doOnError(error ->
                    log.error("Python 서버 종목분석 실패: stockCode={}, errorClass={}, message={}",
                        request.stockCode(), error.getClass().getSimpleName(), error.getMessage())
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
                .flatMap(rawResponse -> {
                    try {
                        StockAnalysisResponse response = PYTHON_MAPPER.readValue(rawResponse, StockAnalysisResponse.class);
                        log.info("역직렬화 결과 [{}]: finalStyleTag={}, scores={}, reportLen={}",
                            request.stockCode(), response.finalStyleTag(), response.scores(),
                            response.report() != null ? response.report().length() : 0);
                        return Mono.just(response);
                    } catch (JsonProcessingException e) {
                        log.error("역직렬화 실패 [{}]: {}", request.stockCode(), e.getMessage());
                        return Mono.error(e);
                    }
                })
                .doOnSuccess(response ->
                    log.info("Python 서버 종목분석 성공: stockCode={}", request.stockCode())
                )
                .onErrorResume(throwable -> {
                    log.error("Python 서버 호출 최종 실패: stockCode={}, errorClass={}, error={}",
                        request.stockCode(), throwable.getClass().getSimpleName(), throwable.getMessage());
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
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class).flatMap(errorBody -> {
                        log.error("Python 포트폴리오 HTTP 오류: status={}, body={}",
                            response.statusCode(),
                            errorBody.length() > 300 ? errorBody.substring(0, 300) : errorBody);
                        return Mono.error(new RuntimeException("Python HTTP " + response.statusCode()));
                    })
                )
                .bodyToMono(String.class)
                .flatMap(rawResponse -> {
                    try {
                        PortfolioAnalysisResponse response = PYTHON_MAPPER.readValue(rawResponse, PortfolioAnalysisResponse.class);
                        log.info("Python 서버 포트폴리오 분석 완료: {}개 종목 분석됨", response.stockDetails().size());
                        return Mono.just(response);
                    } catch (JsonProcessingException e) {
                        log.error("포트폴리오 역직렬화 실패: {}", e.getMessage());
                        return Mono.error(e);
                    }
                })
                .doOnError(error ->
                    log.error("Python 서버 포트폴리오 분석 실패: url={}, errorClass={}, message={}",
                        pythonServerUrl, error.getClass().getSimpleName(), error.getMessage())
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

    // Python 서버에 종목 추천 요청
    public Mono<RecommendResponse> recommendStocks(RecommendRequest request) {
        log.info("Python 서버 종목 추천 요청: {}개 보유종목, persona={}, topN={}, url={}",
                request.stocks().size(), request.persona(), request.topN(), pythonServerUrl);

        return getPythonWebClient()
                .post()
                .uri("/stock/recommend")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RecommendResponse.class)
                .doOnSuccess(response ->
                    log.info("Python 서버 종목 추천 완료: {}개 추천 결과", response.recommendations().size())
                )
                .doOnError(error ->
                    log.error("Python 서버 종목 추천 실패: url={}", pythonServerUrl, error)
                )
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(throwable -> {
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
                    log.error("Python 서버 종목 추천 최종 실패: {}", throwable.getMessage());
                    return new RuntimeException("AI 서버 추천 실패: " + throwable.getMessage(), throwable);
                });
    }
}

