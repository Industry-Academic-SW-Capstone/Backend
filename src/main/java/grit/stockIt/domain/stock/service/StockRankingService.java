package grit.stockIt.domain.stock.service;

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

    // 거래량 상위 종목 조회
    public List<StockRankingDto> getVolumeTopStocks(int limit) {
        try {
            String accessToken = kisTokenManager.getAccessToken();
            
            KisRankingResponseDto response = webClient.get()
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
                    .block();

            return parseVolumeRankingResponse(response, limit);
            
        } catch (Exception e) {
            log.error("거래량 상위 종목 조회 중 오류 발생", e);
            throw new RuntimeException("거래량 상위 종목 조회 실패", e);
        }
    }

    // 거래대금 상위 종목 조회
    public List<StockRankingDto> getAmountTopStocks(int limit) {
        try {
            String accessToken = kisTokenManager.getAccessToken();
            
            KisRankingResponseDto response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                            .queryParam("FID_INPUT_ISCD", "0000")
                            .queryParam("FID_DIV_CLS_CODE", "0")
                            .queryParam("FID_BLNG_CLS_CODE", "3") // 거래대금 순위
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
                    .block();

            return parseAmountRankingResponse(response, limit);
            
        } catch (Exception e) {
            log.error("거래대금 상위 종목 조회 중 오류 발생", e);
            throw new RuntimeException("거래대금 상위 종목 조회 실패", e);
        }
    }

    // 거래량 상위 종목의 주식코드만 반환 (웹소켓 구독용) - DB에 있는 종목만
    public List<String> getVolumeTopStockCodes(int limit) {
        return getVolumeTopStocksFiltered(limit).stream()
                .map(StockRankingDto::stockCode)
                .toList();
    }

    // 거래대금 상위 종목의 주식코드만 반환 (웹소켓 구독용) - DB에 있는 종목만
    public List<String> getAmountTopStockCodes(int limit) {
        return getAmountTopStocksFiltered(limit).stream()
                .map(StockRankingDto::stockCode)
                .toList();
    }

    // 거래량 상위 종목 조회 (DB에 있는 종목만 필터링)
    public List<StockRankingDto> getVolumeTopStocksFiltered(int limit) {
        List<StockRankingDto> allStocks = getVolumeTopStocks(limit);
        return filterStocksInDatabase(allStocks, limit);
    }

    // 거래대금 상위 종목 조회 (DB에 있는 종목만 필터링)
    public List<StockRankingDto> getAmountTopStocksFiltered(int limit) {
        List<StockRankingDto> allStocks = getAmountTopStocks(limit);
        return filterStocksInDatabase(allStocks, limit);
    }

    // 데이터베이스에 있는 종목만 필터링
    private List<StockRankingDto> filterStocksInDatabase(List<StockRankingDto> stocks, int limit) {
        // 종목 코드 리스트 추출
        List<String> stockCodes = stocks.stream()
                .map(StockRankingDto::stockCode)
                .toList();

        // 데이터베이스에서 존재하는 종목들만 조회
        List<Stock> existingStocks = stockRepository.findByCodeIn(stockCodes);
        Set<String> existingStockCodes = existingStocks.stream()
                .map(Stock::getCode)
                .collect(Collectors.toSet());

        log.info("API에서 조회된 종목 수: {}, DB에 존재하는 종목 수: {}", 
                stocks.size(), existingStockCodes.size());

        // DB에 있는 종목만 필터링하고 원하는 개수만큼 반환
        return stocks.stream()
                .filter(stock -> existingStockCodes.contains(stock.stockCode()))
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
     * KisStockDataDto를 StockRankingDto로 변환
     */
    private StockRankingDto mapKisDataToStockRankingDto(KisStockDataDto kisData) {
        return new StockRankingDto(
                kisData.stockCode(),
                kisData.stockName(),
                parseLongValue(kisData.volume()),
                parseLongValue(kisData.amount()),
                0L, // 시가총액 (API에서 제공하지 않음)
                determineMarketType(kisData.stockCode())
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
     * 주식코드로 시장구분 판단
     */
    private String determineMarketType(String stockCode) {
        if (stockCode == null) return "UNKNOWN";
        
        // 코스피: 6자리, 코스닥: 6자리 (일반적으로 구분이 어려우므로 추가 로직 필요)
        // 실제로는 MST 파일의 시장구분 정보를 활용하는 것이 정확함
        return "KOSPI"; // 임시로 KOSPI로 설정
    }
}