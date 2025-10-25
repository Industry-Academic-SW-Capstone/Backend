package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.StockRankingDto;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRankingService {

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;

    // 거래량 상위 종목 조회
    @SuppressWarnings("unchecked")
    public List<StockRankingDto> getVolumeTopStocks(int limit) {
        try {
            String accessToken = kisTokenManager.getAccessToken();
            
            Map<String, Object> response = webClient.get()
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
                    .bodyToMono(Map.class)
                    .block();

            return parseVolumeRankingResponse(response, limit);
            
        } catch (Exception e) {
            log.error("거래량 상위 종목 조회 중 오류 발생", e);
            throw new RuntimeException("거래량 상위 종목 조회 실패", e);
        }
    }

    // 거래대금 상위 종목 조회
    @SuppressWarnings("unchecked")
    public List<StockRankingDto> getAmountTopStocks(int limit) {
        try {
            String accessToken = kisTokenManager.getAccessToken();
            
            Map<String, Object> response = webClient.get()
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
                    .bodyToMono(Map.class)
                    .block();

            return parseAmountRankingResponse(response, limit);
            
        } catch (Exception e) {
            log.error("거래대금 상위 종목 조회 중 오류 발생", e);
            throw new RuntimeException("거래대금 상위 종목 조회 실패", e);
        }
    }

    // 거래량 상위 종목의 주식코드만 반환 (웹소켓 구독용)
    public List<String> getVolumeTopStockCodes(int limit) {
        return getVolumeTopStocks(limit).stream()
                .map(StockRankingDto::stockCode)
                .toList();
    }

    // 거래대금 상위 종목의 주식코드만 반환 (웹소켓 구독용)
    public List<String> getAmountTopStockCodes(int limit) {
        return getAmountTopStocks(limit).stream()
                .map(StockRankingDto::stockCode)
                .toList();
    }

    /**
     * 거래량 순위 응답 파싱
     */
    @SuppressWarnings("unchecked")
    private List<StockRankingDto> parseVolumeRankingResponse(Map<String, Object> response, int limit) {
        try {
            log.info("API 응답 구조 확인: {}", response.keySet());
            
            // 응답 구조에 따라 동적으로 파싱
            Object output = response.get("output");
            log.info("output 타입: {}", output != null ? output.getClass().getSimpleName() : "null");
            
            List<Map<String, Object>> data;
            
            if (output instanceof Map) {
                Map<String, Object> outputMap = (Map<String, Object>) output;
                data = (List<Map<String, Object>>) outputMap.get("data");
            } else if (output instanceof List) {
                // output이 직접 List인 경우
                data = (List<Map<String, Object>>) output;
            } else {
                // 다른 구조인 경우 전체 응답을 데이터로 사용
                data = List.of(response);
            }
            
            log.info("파싱된 데이터 개수: {}", data != null ? data.size() : 0);
            
            return data.stream()
                    .limit(limit)
                    .map(this::mapToStockRankingDto)
                    .toList();
                    
        } catch (Exception e) {
            log.error("거래량 순위 응답 파싱 중 오류 발생", e);
            log.error("응답 내용: {}", response);
            throw new RuntimeException("응답 파싱 실패", e);
        }
    }

    /**
     * 거래대금 순위 응답 파싱
     */
    @SuppressWarnings("unchecked")
    private List<StockRankingDto> parseAmountRankingResponse(Map<String, Object> response, int limit) {
        try {
            log.info("API 응답 구조 확인: {}", response.keySet());
            
            // 응답 구조에 따라 동적으로 파싱
            Object output = response.get("output");
            log.info("output 타입: {}", output != null ? output.getClass().getSimpleName() : "null");
            
            List<Map<String, Object>> data;
            
            if (output instanceof Map) {
                Map<String, Object> outputMap = (Map<String, Object>) output;
                data = (List<Map<String, Object>>) outputMap.get("data");
            } else if (output instanceof List) {
                // output이 직접 List인 경우
                data = (List<Map<String, Object>>) output;
            } else {
                // 다른 구조인 경우 전체 응답을 데이터로 사용
                data = List.of(response);
            }
            
            log.info("파싱된 데이터 개수: {}", data != null ? data.size() : 0);
            
            return data.stream()
                    .limit(limit)
                    .map(this::mapToStockRankingDto)
                    .toList();
                    
        } catch (Exception e) {
            log.error("거래대금 순위 응답 파싱 중 오류 발생", e);
            log.error("응답 내용: {}", response);
            throw new RuntimeException("응답 파싱 실패", e);
        }
    }

    /**
     * API 응답 데이터를 StockRankingDto로 변환
     */
    private StockRankingDto mapToStockRankingDto(Map<String, Object> data) {
        return new StockRankingDto(
                (String) data.get("mksc_shrn_iscd"), // 주식코드
                (String) data.get("hts_kor_isnm"),   // 한글종목명
                parseLongValue(data.get("acml_vol")), // 누적거래량
                parseLongValue(data.get("acml_tr_pbmn")), // 누적거래대금
                parseLongValue(data.get("mkt_cap")),  // 시가총액
                determineMarketType((String) data.get("mksc_shrn_iscd")) // 시장구분
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