package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 개별 종목 데이터 DTO
 */
public record KisStockDataDto(
    @JsonProperty("mksc_shrn_iscd") String stockCode,      // 종목코드
    @JsonProperty("hts_kor_isnm") String stockName,       // 종목명
    @JsonProperty("data_rank") String rank,               // 순위
    @JsonProperty("stck_prpr") String currentPrice,     // 현재가
    @JsonProperty("prdy_vrss_sign") String changeSign,  // 전일대비부호
    @JsonProperty("prdy_vrss") String changeAmount,      // 전일대비
    @JsonProperty("prdy_ctrt") String changeRate,        // 전일대비율
    @JsonProperty("acml_vol") String volume,             // 누적거래량
    @JsonProperty("acml_tr_pbmn") String amount          // 누적거래대금
) {}
