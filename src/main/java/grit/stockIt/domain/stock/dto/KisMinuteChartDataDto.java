package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 분봉 데이터 상세 정보 DTO (output2)
 */
public record KisMinuteChartDataDto(
    @JsonProperty("stck_bsop_date") String date,           // 주식 영업일자
    @JsonProperty("stck_cntg_hour") String time,          // 주식 체결시간 (HHMMSS)
    @JsonProperty("stck_prpr") String currentPrice,       // 주식 현재가 (종가로 사용)
    @JsonProperty("stck_oprc") String openPrice,          // 주식 시가
    @JsonProperty("stck_hgpr") String highPrice,          // 주식 최고가
    @JsonProperty("stck_lwpr") String lowPrice,          // 주식 최저가
    @JsonProperty("cntg_vol") String volume,             // 체결 거래량
    @JsonProperty("acml_tr_pbmn") String amount          // 누적 거래대금
) {}

