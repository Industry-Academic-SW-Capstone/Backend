package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.industry.repository.IndustryRepository;
import grit.stockIt.domain.stock.dto.IndustryStockRankingDto;
import grit.stockIt.domain.stock.dto.StockRankingDto;
import grit.stockIt.domain.stock.dto.KisRankingResponseDto;
import grit.stockIt.domain.stock.dto.KisStockDataDto;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRankingService {

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;
    private final StockRepository stockRepository;
    private final IndustryRepository industryRepository;

    // 거래량 상위 종목 조회 (비동기)
    public Mono<List<StockRankingDto>> getVolumeTopStocks(int limit) {
        String accessToken = kisTokenManager.getAccessToken();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                        .queryParam("FID_INPUT_ISCD", "0000")
                        .queryParam("FID_DIV_CLS_CODE", "0")
                        .queryParam("FID_BLNG_CLS_CODE", "0")
                        .queryParam("FID_TRGT_CLS_CODE", "111111111")
                        .queryParam("FID_TRGT_EXLS_CLS_CODE", "0000000000")
                        .queryParam("FID_INPUT_PRICE_1", "")
                        .queryParam("FID_INPUT_PRICE_2", "")
                        .queryParam("FID_VOL_CNT", "")
                        .queryParam("FID_INPUT_DATE_1", "")
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", "FHPST01710000")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisRankingResponseDto.class)
                .map(response -> parseVolumeRankingResponse(response, limit))
                .doOnError(e -> log.error("거래량 상위 종목 조회 중 오류 발생", e))
                .onErrorResume(e -> Mono.error(new RuntimeException("거래량 상위 종목 조회 실패", e)));
    }

    // 거래대금 상위 종목 조회 (비동기)
    public Mono<List<StockRankingDto>> getAmountTopStocks(int limit) {
        String accessToken = kisTokenManager.getAccessToken();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                        .queryParam("FID_INPUT_ISCD", "0000")
                        .queryParam("FID_DIV_CLS_CODE", "0")
                        .queryParam("FID_BLNG_CLS_CODE", "3") // 거래금액순 (0:평균거래량, 1:거래증가율, 2:평균거래회전율, 3:거래금액순, 4:평균거래금액회전율)
                        .queryParam("FID_TRGT_CLS_CODE", "111111111")
                        .queryParam("FID_TRGT_EXLS_CLS_CODE", "0000000000")
                        .queryParam("FID_INPUT_PRICE_1", "")
                        .queryParam("FID_INPUT_PRICE_2", "")
                        .queryParam("FID_VOL_CNT", "")
                        .queryParam("FID_INPUT_DATE_1", "")
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", "FHPST01710000")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisRankingResponseDto.class)
                .map(response -> parseAmountRankingResponse(response, limit))
                .doOnError(e -> log.error("거래대금 상위 종목 조회 중 오류 발생", e))
                .onErrorResume(e -> Mono.error(new RuntimeException("거래대금 상위 종목 조회 실패", e)));
    }

    // 거래량 상위 종목의 주식코드만 반환 (웹소켓 구독용) - DB에 있는 종목만 (비동기)
    public Mono<List<String>> getVolumeTopStockCodes(int limit) {
        return getVolumeTopStocksFiltered(limit)
                .map(stocks -> stocks.stream()
                        .map(StockRankingDto::stockCode)
                        .toList());
    }

    // 거래대금 상위 종목의 주식코드만 반환 (웹소켓 구독용) - DB에 있는 종목만 (비동기)
    public Mono<List<String>> getAmountTopStockCodes(int limit) {
        return getAmountTopStocksFiltered(limit)
                .map(stocks -> stocks.stream()
                        .map(StockRankingDto::stockCode)
                        .toList());
    }

    // 거래량 상위 종목 조회 (DB에 있는 종목만 필터링) (비동기)
    public Mono<List<StockRankingDto>> getVolumeTopStocksFiltered(int limit) {
        return getVolumeTopStocks(limit)
                .map(allStocks -> filterStocksInDatabase(allStocks, limit));
    }

    // 거래대금 상위 종목 조회 (DB에 있는 종목만 필터링) (비동기)
    public Mono<List<StockRankingDto>> getAmountTopStocksFiltered(int limit) {
        return getAmountTopStocks(limit)
                .map(allStocks -> filterStocksInDatabase(allStocks, limit));
    }

    /**
     * 업종별 인기 종목 조회 (거래대금 기준) - 각 업종별 최대 5개까지 반환
     * @param totalLimit 전체 조회할 종목 수 (한투 API 최대 30개 제한)
     * @param industryCodes 조회할 업종 코드 리스트
     * @return 업종별 인기 종목 리스트 (각 업종 최대 5개)
     */
    public Mono<List<IndustryStockRankingDto>> getPopularStocksByIndustry(
            int totalLimit,
            List<String> industryCodes) {
        
        log.info("업종별 인기 종목 조회 시작 - 전체: {}개, 업종별 최대 5개", totalLimit);
        
        return getAmountTopStocksFiltered(totalLimit)
                .map(allStocks -> {
                    // 종목 코드 리스트 추출
                    List<String> stockCodeList = allStocks.stream()
                            .map(StockRankingDto::stockCode)
                            .filter(code -> code != null && !code.isEmpty())
                            .toList();
                    
                    // DB에서 일괄 조회 (N+1 문제 방지)
                    List<Stock> stockEntities = stockRepository.findByCodeIn(stockCodeList);
                    Map<String, Stock> stockMap = stockEntities.stream()
                            .collect(Collectors.toMap(Stock::getCode, stock -> stock));
                    
                    // 업종 코드별로 그룹화
                    Map<String, List<StockRankingDto>> stocksByIndustry = allStocks.stream()
                            .filter(stock -> {
                                Stock stockEntity = stockMap.get(stock.stockCode());
                                return stockEntity != null && stockEntity.getIndustryCode() != null;
                            })
                            .collect(Collectors.groupingBy(
                                    stock -> {
                                        Stock stockEntity = stockMap.get(stock.stockCode());
                                        return stockEntity != null ? stockEntity.getIndustryCode() : "UNKNOWN";
                                    }
                            ));
                    
                    // 업종 정보 조회
                    List<Industry> industries = industryRepository.findAllById(industryCodes);
                    Map<String, Industry> industryMap = industries.stream()
                            .collect(Collectors.toMap(Industry::getCode, industry -> industry));
                    
                    // 업종별로 정렬 후 최대 5개까지 선택
                    List<IndustryStockRankingDto> result = new ArrayList<>();
                    final int MAX_PER_INDUSTRY = 5;
                    
                    for (String industryCode : industryCodes) {
                        List<StockRankingDto> stocks = stocksByIndustry.getOrDefault(industryCode, new ArrayList<>());
                        
                        // 거래대금 기준 내림차순 정렬 후 최대 5개까지 선택
                        List<StockRankingDto> topStocks = stocks.stream()
                                .sorted((a, b) -> Long.compare(b.amount(), a.amount()))
                                .limit(MAX_PER_INDUSTRY)
                                .toList();
                        
                        // 종목이 있으면 반환 (없어도 빈 배열 반환하지 않음)
                        if (!topStocks.isEmpty()) {
                            Industry industry = industryMap.get(industryCode);
                            String industryName = industry != null ? industry.getName() : null;
                            
                            result.add(new IndustryStockRankingDto(
                                    industryCode,
                                    industryName,
                                    topStocks
                            ));
                        }
                    }
                    
                    log.info("업종별 인기 종목 조회 완료 - {}개 업종", result.size());
                    return result;
                })
                .doOnError(e -> log.error("업종별 인기 종목 조회 중 오류 발생", e))
                .onErrorResume(e -> Mono.error(new RuntimeException("업종별 인기 종목 조회 실패", e)));
    }


    // 데이터베이스에 있는 종목만 필터링 (marketType을 DB에서 조회)
    private List<StockRankingDto> filterStocksInDatabase(List<StockRankingDto> stocks, int limit) {
        // 종목 코드 리스트 추출
        List<String> stockCodes = stocks.stream()
                .map(StockRankingDto::stockCode)
                .toList();

        // 데이터베이스에서 존재하는 종목들만 조회
        List<Stock> existingStocks = stockRepository.findByCodeIn(stockCodes);

        // 종목 코드 -> Stock 엔티티 맵 생성 (marketType 조회용)
        Map<String, Stock> stockMap = existingStocks.stream()
                .collect(Collectors.toMap(Stock::getCode, stock -> stock));

        Set<String> existingStockCodes = stockMap.keySet();

        log.info("API에서 조회된 종목 수: {}, DB에 존재하는 종목 수: {}",
                stocks.size(), existingStockCodes.size());

        // DB에 있는 종목만 필터링하고, DB에서 조회한 marketType으로 DTO 재생성
        return stocks.stream()
                .filter(stock -> existingStockCodes.contains(stock.stockCode()))
                .map(stockDto -> {
                    Stock stock = stockMap.get(stockDto.stockCode());
                    // DB의 marketType 정보로 DTO 재생성 (가격 정보 유지)
                    return new StockRankingDto(
                            stockDto.stockCode(),
                            stockDto.stockName(),
                            stockDto.volume(),
                            stockDto.amount(),
                            stock.getMarketType(),
                            // 가격 정보 유지
                            stockDto.currentPrice(),
                            stockDto.changeAmount(),
                            stockDto.changeRate(),
                            stockDto.changeSign()
                    );
                })
                .limit(limit)
                .toList();
    }

    /**
     * 거래량 순위 응답 파싱 (DTO 사용)
     */
    private List<StockRankingDto> parseVolumeRankingResponse(KisRankingResponseDto response, int limit) {
        try {
            log.info("API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());

            // output이 List인지 Map인지 확인하여 처리
            List<KisStockDataDto> stockDataList = parseOutputData(response.output());

            log.info("파싱된 데이터 개수: {}", stockDataList.size());

            return stockDataList.stream()
                    .limit(limit)
                    .map(this::mapKisDataToStockRankingDto)
                    .toList();

        } catch (Exception e) {
            log.error("거래량 순위 응답 파싱 중 오류 발생", e);
            log.error("응답 내용: {}", response);
            throw new RuntimeException("응답 파싱 실패", e);
        }
    }

    /**
     * 거래대금 순위 응답 파싱 (DTO 사용)
     */
    private List<StockRankingDto> parseAmountRankingResponse(KisRankingResponseDto response, int limit) {
        try {
            log.info("API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());

            // output이 List인지 Map인지 확인하여 처리
            List<KisStockDataDto> stockDataList = parseOutputData(response.output());

            log.info("파싱된 데이터 개수: {}", stockDataList.size());

            return stockDataList.stream()
                    .limit(limit)
                    .map(this::mapKisDataToStockRankingDto)
                    .toList();

        } catch (Exception e) {
            log.error("거래대금 순위 응답 파싱 중 오류 발생", e);
            log.error("응답 내용: {}", response);
            throw new RuntimeException("응답 파싱 실패", e);
        }
    }

    /**
     * output 데이터를 KisStockDataDto 리스트로 변환
     */
    @SuppressWarnings("unchecked")
    private List<KisStockDataDto> parseOutputData(Object output) {
        if (output instanceof List) {
            // output이 직접 List인 경우
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) output;
            return dataList.stream()
                    .map(this::mapToKisStockDataDto)
                    .toList();
        } else if (output instanceof Map) {
            // output이 Map이고 "data" 키를 가진 경우
            Map<String, Object> outputMap = (Map<String, Object>) output;
            Object data = outputMap.get("data");
            if (data instanceof List) {
                List<Map<String, Object>> dataList = (List<Map<String, Object>>) data;
                return dataList.stream()
                        .map(this::mapToKisStockDataDto)
                        .toList();
            }
        }

        // 예외적인 경우 빈 리스트 반환
        log.warn("예상하지 못한 output 구조: {}", output);
        return List.of();
    }

    /**
     * Map을 KisStockDataDto로 변환
     */
    private KisStockDataDto mapToKisStockDataDto(Map<String, Object> data) {
        return new KisStockDataDto(
                (String) data.get("mksc_shrn_iscd"),
                (String) data.get("hts_kor_isnm"),
                (String) data.get("data_rank"),
                (String) data.get("stck_prpr"),
                (String) data.get("prdy_vrss_sign"),
                (String) data.get("prdy_vrss"),
                (String) data.get("prdy_ctrt"),
                (String) data.get("acml_vol"),
                (String) data.get("acml_tr_pbmn")
        );
    }

    /**
     * KisStockDataDto를 StockRankingDto로 변환 (임시 marketType 사용)
     * 실제 marketType은 DB에서 조회한 뒤 다시 설정됨
     */
    private StockRankingDto mapKisDataToStockRankingDto(KisStockDataDto kisData) {
        return new StockRankingDto(
                kisData.stockCode(),
                kisData.stockName(),
                parseLongValue(kisData.volume()),
                parseLongValue(kisData.amount()),
                "UNKNOWN", // 임시값, DB 조회 후 올바른 값으로 교체됨

                // 가격 정보 추가
                parseIntValue(kisData.currentPrice()),
                parseIntValue(kisData.changeAmount()),
                kisData.changeRate(),
                StockRankingDto.PriceChangeSign.fromCode(kisData.changeSign())
        );
    }


    /**
     * Object를 Long으로 안전하게 변환
     */
    private Long parseLongValue(Object value) {
        if (value == null) return 0L;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * String을 Integer로 안전하게 변환
     */
    private Integer parseIntValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            // 음수 처리 (전일대비는 음수일 수 있음)
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Integer 파싱 실패: {}", value);
            return 0;
        }
    }
}