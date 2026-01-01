package grit.stockIt.domain.stock.analysis.service;

import grit.stockIt.domain.mission.event.StockAnalyzedEvent;
import grit.stockIt.domain.stock.analysis.dto.*;
import grit.stockIt.domain.stock.analysis.repository.RedisStockAnalysisRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

// 종목분석 서비스
@Slf4j
@Service
public class StockAnalysisService {

    private final StockDetailService stockDetailService;
    private final KisFinancialRatioService kisFinancialRatioService;
    private final KisDividendService kisDividendService;
    private final PythonAnalysisClient pythonAnalysisClient;
    private final RedisStockAnalysisRepository redisStockAnalysisRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StockAnalysisService(
            @Lazy StockDetailService stockDetailService,
            KisFinancialRatioService kisFinancialRatioService,
            KisDividendService kisDividendService,
            PythonAnalysisClient pythonAnalysisClient,
            RedisStockAnalysisRepository redisStockAnalysisRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.stockDetailService = stockDetailService;
        this.kisFinancialRatioService = kisFinancialRatioService;
        this.kisDividendService = kisDividendService;
        this.pythonAnalysisClient = pythonAnalysisClient;
        this.redisStockAnalysisRepository = redisStockAnalysisRepository;
        this.eventPublisher = eventPublisher;
    }

    // 종목분석 수행
    public Mono<StockAnalysisResponse> analyzeStock(String stockCode, String email) {
        log.info("종목분석 시작: stockCode={}, requestUser={}", stockCode, email);

        // 1. 데이터 수집 (병렬 처리)
        Mono<MarketData> marketDataMono = getMarketData(stockCode);
        Mono<FinancialData> financialDataMono = getFinancialData(stockCode);
        Mono<DividendData> dividendDataMono = getDividendData(stockCode);

        // 2. 모든 데이터 수집 후 Python 서버 호출
        return Mono.zip(marketDataMono, financialDataMono, dividendDataMono)
                .flatMap(tuple -> {
                    MarketData marketData = tuple.getT1();
                    FinancialData financialData = tuple.getT2();
                    DividendData dividendData = tuple.getT3();

                    // 3. Python 서버 요청 DTO 생성
                    StockAnalysisRequest request = new StockAnalysisRequest(
                            stockCode,
                            marketData.marketCap() != null ? marketData.marketCap().doubleValue() : null,
                            marketData.per(),
                            marketData.pbr(),
                            financialData.roe(),
                            financialData.debtRatio(),
                            dividendData.dividendYield() != null ? dividendData.dividendYield() : 0.0
                    );

                    // 4. Python 서버 호출
                    return pythonAnalysisClient.analyze(request);
                })
                .doOnSuccess(response -> {
                    // [핵심] 분석 성공 시 이벤트 발행
                    // email이 있는 경우(로그인 유저)에만 발행
                    if (email != null) {
                        log.info("종목 분석 성공 이벤트 발행: {}", email);
                        eventPublisher.publishEvent(new StockAnalyzedEvent(email, stockCode));
                    }
                })
                .doOnError(e -> log.error("종목분석 실패: stockCode={}", stockCode, e))
                .onErrorMap(throwable -> {
                    log.error("종목분석 최종 실패: stockCode={}", stockCode, throwable);
                    return new RuntimeException("종목 분석 중 오류가 발생했습니다: " + throwable.getMessage(), throwable);
                });
    }

    // 시장 데이터 조회 (캐시 우선)
    public Mono<MarketData> getMarketData(String stockCode) {
        // 1. 캐시 확인
        return Mono.fromCallable(() -> redisStockAnalysisRepository.getMarketData(stockCode))
                .flatMap(cachedData -> {
                    if (cachedData.isPresent()) {
                        log.info("캐시에서 시장 데이터 조회: stockCode={}", stockCode);
                        return Mono.just(cachedData.get());
                    }

                    // 2. 캐시에 없으면 KIS API 호출
                    log.info("캐시에 없어 KIS API 호출 (시장 데이터): stockCode={}", stockCode);
                    return stockDetailService.getStockDetail(stockCode)
                            .map(stockDetail -> {
                                // StockDetailDto에서 시가총액, PER, PBR 추출
                                Long marketCap = stockDetail.marketCap() != null ? stockDetail.marketCap() : null;
                                Double per = stockDetail.per() != null ? stockDetail.per() : null;
                                Double pbr = stockDetail.pbr() != null ? stockDetail.pbr() : null;

                                MarketData data = new MarketData(marketCap, per, pbr);
                                // 3. 캐시 저장
                                redisStockAnalysisRepository.saveMarketData(stockCode, data);
                                return data;
                            });
                })
                .onErrorResume(e -> {
                    log.error("시장 데이터 조회 실패: stockCode={}", stockCode, e);
                    // 기본값 반환
                    return Mono.just(new MarketData(null, null, null));
                });
    }

    // 재무 데이터 조회 (캐시 우선)
    public Mono<FinancialData> getFinancialData(String stockCode) {
        // 1. 캐시 확인
        return Mono.fromCallable(() -> redisStockAnalysisRepository.getFinancialData(stockCode))
                .flatMap(cachedData -> {
                    if (cachedData.isPresent()) {
                        log.info("캐시에서 재무 데이터 조회: stockCode={}", stockCode);
                        return Mono.just(cachedData.get());
                    }

                    // 2. 캐시에 없으면 KIS API 호출
                    log.info("캐시에 없어 KIS API 호출 (재무 데이터): stockCode={}", stockCode);
                    return kisFinancialRatioService.getFinancialRatio(stockCode)
                            .map(output -> {
                                Double roe = parseDoubleValue(output.roeVal());
                                Double debtRatio = parseDoubleValue(output.lbltRate());

                                FinancialData data = new FinancialData(roe, debtRatio);
                                // 3. 캐시 저장
                                redisStockAnalysisRepository.saveFinancialData(stockCode, data);
                                return data;
                            });
                })
                .onErrorResume(e -> {
                    log.error("재무 데이터 조회 실패: stockCode={}", stockCode, e);
                    // 기본값 반환
                    return Mono.just(new FinancialData(null, null));
                });
    }

    // 배당 데이터 조회 (캐시 우선)
    public Mono<DividendData> getDividendData(String stockCode) {
        // 1. 캐시 확인
        return Mono.fromCallable(() -> redisStockAnalysisRepository.getDividendData(stockCode))
                .flatMap(cachedData -> {
                    if (cachedData.isPresent()) {
                        log.info("캐시에서 배당 데이터 조회: stockCode={}", stockCode);
                        return Mono.just(cachedData.get());
                    }

                    // 2. 캐시에 없으면 KIS API 호출
                    log.info("캐시에 없어 KIS API 호출 (배당 데이터): stockCode={}", stockCode);
                    return kisDividendService.getDividendInfo(stockCode)
                            .map(output -> {
                                if (output == null) {
                                    // 배당 정보가 없는 경우
                                    DividendData data = new DividendData(0.0);
                                    redisStockAnalysisRepository.saveDividendData(stockCode, data);
                                    return data;
                                }

                                Double dividendYield = parseDoubleValue(output.diviRate());
                                DividendData data = new DividendData(dividendYield != null ? dividendYield : 0.0);
                                // 3. 캐시 저장
                                redisStockAnalysisRepository.saveDividendData(stockCode, data);
                                return data;
                            })
                            .defaultIfEmpty(new DividendData(0.0));  // null인 경우 기본값
                })
                .onErrorResume(e -> {
                    log.error("배당 데이터 조회 실패: stockCode={}", stockCode, e);
                    // 기본값 반환
                    return Mono.just(new DividendData(0.0));
                });
    }

    // String을 Double로 안전하게 변환
    private Double parseDoubleValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Double 파싱 실패: {}", value);
            return null;
        }
    }
}

