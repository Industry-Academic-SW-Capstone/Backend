package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.stock.analysis.dto.MarketData;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import grit.stockIt.domain.stock.analysis.service.StockAnalysisService;
import grit.stockIt.domain.stock.dto.KisStockDetailDto;
import grit.stockIt.domain.stock.dto.KisStockDetailResponseDto;
import grit.stockIt.domain.stock.dto.StockDetailDto;
import grit.stockIt.domain.stock.dto.StockRankingDto;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * 주식 상세 정보 조회 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDetailService {

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper;
    private final RedisMarketDataRepository redisMarketDataRepository;
    private final StockAnalysisService stockAnalysisService;

    // 주식 상세 정보 조회
    public Mono<StockDetailDto> getStockDetail(String stockCode) {
        log.info("주식 상세 정보 조회 요청: {}", stockCode);
        
        // DB에서 종목 정보 조회
        Optional<Stock> stockOptional = stockRepository.findById(stockCode);
        if (stockOptional.isEmpty()) {
            log.warn("종목을 찾을 수 없습니다: {}", stockCode);
            return Mono.error(new RuntimeException("종목을 찾을 수 없습니다: " + stockCode));
        }
        
        Stock stock = stockOptional.get();
        
        // KIS API에서 시세 정보 조회와 AI 분석을 병렬로 수행
        Mono<KisStockDetailDto> kisDetailMono = getStockPriceFromKis(stockCode);
        Mono<StockAnalysisResponse> aiAnalysisMono = stockAnalysisService.analyzeStock(stockCode, null)
                .onErrorResume(e -> {
                    log.warn("AI 분석 실패, 기본값으로 처리: stockCode={}, error={}", stockCode, e.getMessage());
                    // AI 분석 실패 시 기본 응답 반환
                    return Mono.just(new StockAnalysisResponse(
                        stockCode,
                        null,
                        null,
                        null,
                        false,
                        "AI 분석에 실패했습니다."
                    ));
                });
        
        return Mono.zip(kisDetailMono, aiAnalysisMono)
                .map(tuple -> {
                    KisStockDetailDto kisDetail = tuple.getT1();
                    StockAnalysisResponse aiAnalysis = tuple.getT2();
                    
                    // analyzable 값으로 tradeable 판단
                    boolean tradeable = aiAnalysis.analyzable() != null && aiAnalysis.analyzable();
                    
                    // DTO 변환
                    return mapToStockDetailDto(
                            stockCode,
                            stock,
                            kisDetail,
                            aiAnalysis,
                            tradeable
                    );
                })
                .doOnError(e -> log.error("주식 상세 정보 조회 중 오류 발생: {}", stockCode, e))
                .onErrorResume(e -> Mono.error(new RuntimeException("주식 상세 정보 조회 실패: " + stockCode, e)));
    }

    // KIS API에서 현재가만 조회 (캐시 우선)
    public Mono<BigDecimal> getCurrentPrice(String stockCode) {
        // 1. Redis 캐시에서 먼저 조회
        return Mono.fromCallable(() -> redisMarketDataRepository.getLastPrice(stockCode))
                .flatMap(cachedPrice -> {
                    if (cachedPrice.isPresent()) {
                        log.info("캐시에서 현재가 조회: stockCode={}, price={}", stockCode, cachedPrice.get());
                        return Mono.just(cachedPrice.get());
                    }
                    
                    // 2. 캐시에 없으면 KIS API 호출
                    log.info("캐시에 없어 KIS API 호출: stockCode={}", stockCode);
                    return getStockPriceFromKis(stockCode)
                            .map(kisDetail -> {
                                String priceStr = kisDetail.currentPrice();
                                if (priceStr == null || priceStr.trim().isEmpty()) {
                                    throw new RuntimeException("KIS API에서 현재가를 가져올 수 없습니다: " + stockCode);
                                }
                                try {
                                    BigDecimal price = new BigDecimal(priceStr.trim());
                                    // 3. Redis에 캐시 저장
                                    redisMarketDataRepository.updateLastPrice(stockCode, price);
                                    log.info("KIS API에서 현재가 조회 및 캐시 저장: stockCode={}, price={}", stockCode, price);
                                    return price;
                                } catch (NumberFormatException e) {
                                    log.error("현재가 파싱 실패: stockCode={}, price={}", stockCode, priceStr, e);
                                    throw new RuntimeException("현재가 파싱 실패: " + stockCode, e);
                                }
                            });
                })
                .doOnError(e -> log.error("현재가 조회 실패: stockCode={}", stockCode, e));
    }

    // KIS API에서 시장 데이터만 조회
    public Mono<MarketData> getMarketDataFromKis(String stockCode) {
        return getStockPriceFromKis(stockCode)
                .map(kisDetail -> {
                    Long marketCap = parseLongValue(kisDetail.marketCap());
                    Double per = parseDoubleValueForMarketData(kisDetail.per());
                    Double pbr = parseDoubleValueForMarketData(kisDetail.pbr());
                    return new MarketData(marketCap, per, pbr);
                })
                .doOnError(e -> log.error("시장 데이터 조회 실패: stockCode={}", stockCode, e));
    }

    // KIS API에서 주식 현재가 시세 조회
    private Mono<KisStockDetailDto> getStockPriceFromKis(String stockCode) {
        String accessToken = kisTokenManager.getAccessToken();
        
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")  // J: 주식
                        .queryParam("FID_INPUT_ISCD", stockCode)     // 종목코드
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", "FHKST01010100")  // 주식현재가 시세 조회 TR ID
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisStockDetailResponseDto.class)
                .map(response -> {
                    log.info("KIS API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());
                    
                    if (!"0".equals(response.rtCd())) {
                        throw new RuntimeException("KIS API 오류: " + response.msg1());
                    }
                    
                    if (response.output() == null) {
                        throw new RuntimeException("KIS API 응답 데이터가 없습니다.");
                    }
                    
                    // output을 KisStockDetailDto로 변환
                    return parseOutputData(response.output());
                })
                .doOnError(e -> log.error("KIS API 주식현재가 시세 조회 중 오류 발생", e));
    }

    // KIS API 응답과 DB 정보를 통합하여 StockDetailDto로 변환
    private StockDetailDto mapToStockDetailDto(
            String stockCode,
            Stock stock,
            KisStockDetailDto kisDetail,
            StockAnalysisResponse aiAnalysis,
            boolean tradeable
    ) {
        return new StockDetailDto(
                stockCode,
                kisDetail.stockName() != null ? kisDetail.stockName() : stock.getName(),
                parseIntValue(kisDetail.currentPrice()),
                parseIntValue(kisDetail.changeAmount()),
                kisDetail.changeRate() != null ? kisDetail.changeRate() : "0",
                StockRankingDto.PriceChangeSign.fromCode(kisDetail.changeSign()),
                parseLongValue(kisDetail.volume()),
                parseLongValue(kisDetail.amount()),
                parseLongValue(kisDetail.marketCap()),
                parseDoubleValue(kisDetail.per()),
                parseDoubleValue(kisDetail.eps()),
                parseDoubleValue(kisDetail.pbr()),
                parseIntValue(kisDetail.faceValue()),
                parseIntValue(kisDetail.highPrice()),
                parseIntValue(kisDetail.lowPrice()),
                parseIntValue(kisDetail.openPrice()),
                parseIntValue(kisDetail.previousClosePrice()),
                aiAnalysis.finalStyleTag(),
                aiAnalysis.styleDescription(),
                tradeable,
                aiAnalysis.reason()
        );
    }

    // String을 Integer로 안전하게 변환
    private Integer parseIntValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Integer 파싱 실패: {}", value);
            return 0;
        }
    }

    // String을 Long으로 안전하게 변환
    private Long parseLongValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Long 파싱 실패: {}", value);
            return 0L;
        }
    }

    // String을 Double로 안전하게 변환
    private Double parseDoubleValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0.0;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Double 파싱 실패: {}", value);
            return 0.0;
        }
    }

    // String을 Double로 안전하게 변환
    private Double parseDoubleValueForMarketData(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Double 파싱 실패: {}", value);
            return null;
        }
    }

    // output 데이터를 KisStockDetailDto로 변환
    @SuppressWarnings("unchecked")
    private KisStockDetailDto parseOutputData(Object output) {
        try {
            if (output instanceof Map) {
                // output이 Map인 경우 직접 변환
                Map<String, Object> outputMap = (Map<String, Object>) output;
                return objectMapper.convertValue(outputMap, KisStockDetailDto.class);
            } else if (output instanceof KisStockDetailDto) {
                // 이미 변환된 경우
                return (KisStockDetailDto) output;
            } else {
                log.warn("예상하지 못한 output 구조: {}", output.getClass());
                throw new RuntimeException("KIS API 응답 데이터 형식이 올바르지 않습니다.");
            }
        } catch (Exception e) {
            log.error("output 데이터 파싱 중 오류 발생", e);
            throw new RuntimeException("KIS API 응답 데이터 파싱 실패", e);
        }
    }
}

