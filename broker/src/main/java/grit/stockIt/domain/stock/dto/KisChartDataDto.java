package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 차트 데이터 상세 정보 DTO
 * output2 배열의 각 항목
 */
public record KisChartDataDto(
    @JsonProperty("stck_bsop_date") String date,           // 기준일자
    @JsonProperty("stck_clpr") String closePrice,        // 종가
    @JsonProperty("stck_oprc") String openPrice,          // 시가
    @JsonProperty("stck_hgpr") String highPrice,          // 고가
    @JsonProperty("stck_lwpr") String lowPrice,          // 저가
    @JsonProperty("acml_vol") String volume,            // 누적거래량
    @JsonProperty("acml_tr_pbmn") String amount,        // 누적거래대금
    @JsonProperty("prdy_vrss") String changeAmount,      // 전일대비
    @JsonProperty("prdy_vrss_sign") String changeSign,   // 전일대비부호 (2:상승, 5:하락)
    @JsonProperty("prdy_ctrt") String changeRate         // 전일대비율 (선택적, output2에는 없을 수 있음)
) {}

