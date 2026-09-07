package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.KisRankingResponse;
import grit.stockIt.domain.stock.dto.KisStockDataDto;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * KIS 순위 응답을 StockRankingResponse로 변환하는 순수 계산 (의존 0).
 *
 * KIS 와이어 포맷이 바뀔 때 이 클래스가 바뀐다. 협력자가 없으므로 KIS API나 DB 없이
 * 단위 테스트로 검증할 수 있다.
 */
@Slf4j
@Service
public class StockRankingParsingService {

    public List<StockRankingResponse> parseAmountRankingResponse(KisRankingResponse response, int limit) {
        try {
            log.info("API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());

            List<KisStockDataDto> stockDataList = parseOutputData(response.output());

            log.info("파싱된 데이터 개수: {}", stockDataList.size());

            return stockDataList.stream()
                    .limit(limit)
                    .map(this::mapKisDataToStockRankingResponse)
                    .toList();

        } catch (Exception e) {
            log.error("거래대금 순위 응답 파싱 중 오류 발생", e);
            log.error("응답 내용: {}", response);
            throw new RuntimeException("응답 파싱 실패", e);
        }
    }

    public List<StockRankingResponse> parseFluctuationRankingResponse(KisRankingResponse response, int limit) {
        try {
            log.info("API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());
            List<Map<String, Object>> dataList = extractOutputMapList(response.output());
            log.info("파싱된 데이터 개수(등락): {}", dataList.size());

            return dataList.stream()
                    .limit(limit)
                    .map(this::mapToKisStockDataDtoFluctuation)
                    .map(this::mapKisDataToStockRankingResponse)
                    .toList();
        } catch (Exception e) {
            log.error("등락 순위 응답 파싱 중 오류 발생", e);
            log.error("응답 내용: {}", response);
            throw new RuntimeException("응답 파싱 실패(등락)", e);
        }
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> extractOutputMapList(Object output) {
        if (output instanceof List) {
            return (List<Map<String, Object>>) output;
        } else if (output instanceof Map) {
            Map<String, Object> outputMap = (Map<String, Object>) output;
            Object data = outputMap.get("data");
            if (data instanceof List) {
                return (List<Map<String, Object>>) data;
            }
        }
        log.warn("예상하지 못한 output 구조(등락): {}", output);
        return List.of();
    }

    @SuppressWarnings("unchecked")
    List<KisStockDataDto> parseOutputData(Object output) {
        if (output instanceof List) {
            List<Map<String, Object>> dataList = (List<Map<String, Object>>) output;
            return dataList.stream()
                    .map(this::mapToKisStockDataDto)
                    .toList();
        } else if (output instanceof Map) {
            Map<String, Object> outputMap = (Map<String, Object>) output;
            Object data = outputMap.get("data");
            if (data instanceof List) {
                List<Map<String, Object>> dataList = (List<Map<String, Object>>) data;
                return dataList.stream()
                        .map(this::mapToKisStockDataDto)
                        .toList();
            }
        }

        log.warn("예상하지 못한 output 구조: {}", output);
        return List.of();
    }

    KisStockDataDto mapToKisStockDataDto(Map<String, Object> data) {
        // 종목코드: 등락 API는 stck_shrn_iscd, 거래대금/거래량 랭킹은 mksc_shrn_iscd를 사용
        String stockCode = (String) (data.get("mksc_shrn_iscd") != null
                ? data.get("mksc_shrn_iscd")
                : data.get("stck_shrn_iscd"));
        return toDto(data, stockCode);
    }

    KisStockDataDto mapToKisStockDataDtoFluctuation(Map<String, Object> data) {
        String stockCode = (String) data.get("stck_shrn_iscd"); // 등락은 무조건 stck_shrn_iscd
        return toDto(data, stockCode);
    }

    /**
     * 종목코드가 결정된 뒤 나머지 8개 필드를 조립한다.
     * 인접한 동일 타입(volume/amount=String)을 뒤바꿔 써도 컴파일되므로
     * 조립 지점을 하나로 유지한다.
     */
    private KisStockDataDto toDto(Map<String, Object> data, String stockCode) {
        // 거래대금: 등락 API에는 acml_tr_pbmn이 없을 수 있으므로 기본값 "0"
        String amount = (String) (data.getOrDefault("acml_tr_pbmn", "0"));

        return new KisStockDataDto(
                stockCode,
                (String) data.get("hts_kor_isnm"),
                (String) data.get("data_rank"),
                (String) data.get("stck_prpr"),
                (String) data.get("prdy_vrss_sign"),
                (String) data.get("prdy_vrss"),
                (String) data.get("prdy_ctrt"),
                (String) data.get("acml_vol"),
                amount
        );
    }

    StockRankingResponse mapKisDataToStockRankingResponse(KisStockDataDto kisData) {
        return new StockRankingResponse(
                kisData.stockCode(),
                kisData.stockName(),
                parseLongValue(kisData.volume()),
                parseLongValue(kisData.amount()),
                "UNKNOWN", // 임시값, DB 조회 후 올바른 값으로 교체됨

                parseIntValue(kisData.currentPrice()),
                parseIntValue(kisData.changeAmount()),
                kisData.changeRate(),
                StockRankingResponse.PriceChangeSign.fromCode(kisData.changeSign())
        );
    }

    Long parseLongValue(Object value) {
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

    Integer parseIntValue(String value) {
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
