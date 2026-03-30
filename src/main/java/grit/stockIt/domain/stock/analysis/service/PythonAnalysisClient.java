package grit.stockIt.domain.stock.analysis.service;

import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisRequest;
import grit.stockIt.domain.stock.analysis.dto.PortfolioAnalysisResponse;
import grit.stockIt.domain.stock.analysis.dto.RecommendRequest;
import grit.stockIt.domain.stock.analysis.dto.RecommendResponse;
import grit.stockIt.domain.stock.analysis.dto.ReportStreamRequest;
import grit.stockIt.domain.stock.analysis.dto.ScoreRequest;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisRequest;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

// Python AI 서버 클라이언트
@Slf4j
@Service
@RequiredArgsConstructor
public class PythonAnalysisClient {

    // Python 응답 전용 ObjectMapper: Spring Boot의 SNAKE_CASE 전략 충돌 방지, @JsonProperty 기반으로만 처리
    private static final ObjectMapper PYTHON_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class).flatMap(errorBody -> {
                        log.error("Python HTTP 오류: stockCode={}, status={}, body={}",
                            request.stockCode(), response.statusCode(),
                            errorBody.length() > 300 ? errorBody.substring(0, 300) : errorBody);
                        return Mono.error(new RuntimeException("Python HTTP " + response.statusCode()));
                    })
                )
                .bodyToMono(String.class)
                .flatMap(rawResponse -> {
                    log.info("Python 원시 응답 [{}]: {}", request.stockCode(),
                        rawResponse.length() > 300 ? rawResponse.substring(0, 300) + "..." : rawResponse);
                    try {
                        StockAnalysisResponse response = PYTHON_MAPPER.readValue(rawResponse, StockAnalysisResponse.class);
                        log.info("Python 역직렬화 성공 [{}]: analyzable={}, scores={}",
                            request.stockCode(), response.analyzable(), response.scores());
                        return Mono.just(response);
                    } catch (JsonProcessingException e) {
                        log.error("Python 응답 역직렬화 실패 [{}]: {}", request.stockCode(), e.getMessage());
                        return Mono.error(e);
                    }
                })
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

    // Python 서버에 빠른 스코어링만 요청 (포트폴리오 있으면 실제 유사도 계산)
    public Mono<StockAnalysisResponse> score(StockAnalysisRequest request, ScoreRequest scoreRequest) {
        log.info("Python 서버 스코어링 요청: stockCode={}, hasPortfolio={}",
                request.stockCode(), scoreRequest != null && scoreRequest.portfolioStocks() != null);

        // 명시적 snake_case Map 구성 (WebClient 직렬화 이슈 방지)
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("stock_code", request.stockCode());
        body.put("market_cap", request.marketCap() != null ? request.marketCap() : 0.0);
        body.put("per", request.per() != null ? request.per() : 0.0);
        body.put("pbr", request.pbr() != null ? request.pbr() : 0.0);
        body.put("roe", request.roe() != null ? request.roe() : 0.0);
        body.put("debt_ratio", request.debtRatio() != null ? request.debtRatio() : 0.0);
        body.put("dividend_yield", request.dividendYield() != null ? request.dividendYield() : 0.0);

        // 포트폴리오 + 페르소나 추가 (있으면)
        if (scoreRequest != null && scoreRequest.portfolioStocks() != null) {
            java.util.List<java.util.Map<String, Object>> portfolioList = scoreRequest.portfolioStocks().stream()
                    .map(s -> java.util.Map.<String, Object>of(
                            "stock_code", s.stockCode(),
                            "market_cap", s.marketCap() != null ? s.marketCap() : 0.0,
                            "per", s.per() != null ? s.per() : 0.0,
                            "pbr", s.pbr() != null ? s.pbr() : 0.0,
                            "roe", s.roe() != null ? s.roe() : 0.0,
                            "debt_ratio", s.debtRatio() != null ? s.debtRatio() : 0.0,
                            "dividend_yield", s.dividendYield() != null ? s.dividendYield() : 0.0,
                            "investment_amount", s.investmentAmount() != null ? s.investmentAmount() : 0.0
                    ))
                    .toList();
            body.put("portfolio_stocks", portfolioList);
        }
        if (scoreRequest != null && scoreRequest.persona() != null) {
            body.put("persona", scoreRequest.persona());
        }

        return getPythonWebClient()
                .post()
                .uri("/stock/score")
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class).flatMap(errorBody -> {
                        log.error("Python /stock/score 오류: stockCode={}, status={}", request.stockCode(), response.statusCode());
                        return Mono.error(new RuntimeException("Python HTTP " + response.statusCode()));
                    })
                )
                .bodyToMono(String.class)
                .flatMap(rawResponse -> {
                    try {
                        StockAnalysisResponse response = PYTHON_MAPPER.readValue(rawResponse, StockAnalysisResponse.class);
                        return Mono.just(response);
                    } catch (JsonProcessingException e) {
                        log.error("Python /stock/score 역직렬화 실패: {}", e.getMessage());
                        return Mono.error(e);
                    }
                })
                .onErrorResume(throwable -> {
                    log.error("Python 스코어링 최종 실패: stockCode={}", request.stockCode(), throwable);
                    return Mono.just(new StockAnalysisResponse(
                        request.stockCode(), null, null, null, false, "AI 서버 오류로 스코어링에 실패했습니다."
                    ));
                });
    }

    // Python 서버 SSE 리포트 스트림 프록시
    public Flux<String> streamReport(ReportStreamRequest request) {
        log.info("Python 서버 리포트 스트리밍 요청: stockCode={}", request.stockCode());

        // WebClient Jackson 직렬화 이슈 방지: 명시적 snake_case Map 구성
        java.util.Map<String, Object> body = java.util.Map.of(
                "stock_code", request.stockCode(),
                "stock_name", request.stockName(),
                "style_tag", request.styleTag(),
                "growth_score", request.growthScore() != null ? request.growthScore() : 0.0,
                "stability_score", request.stabilityScore() != null ? request.stabilityScore() : 0.0,
                "composite_score", request.compositeScore() != null ? request.compositeScore() : 0.0
        );

        return getPythonWebClient()
                .post()
                .uri("/stock/report/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response ->
                    response.bodyToMono(String.class).flatMap(errorBody -> {
                        log.error("Python /stock/report/stream 오류: stockCode={}, status={}, body={}",
                            request.stockCode(), response.statusCode(),
                            errorBody.length() > 300 ? errorBody.substring(0, 300) : errorBody);
                        return Mono.error(new RuntimeException("Python HTTP " + response.statusCode()));
                    })
                )
                .bodyToFlux(String.class)
                .doOnError(e -> log.error("Python 리포트 스트리밍 실패: stockCode={}", request.stockCode(), e))
                .onErrorResume(e -> Flux.just("data: {\"type\": \"done\"}\n\n"));
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

