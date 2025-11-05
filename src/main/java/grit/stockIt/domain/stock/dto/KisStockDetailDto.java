package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 주식현재가 시세 상세 정보 DTO
 */
public record KisStockDetailDto(
    @JsonProperty("hts_kor_isnm") String stockName,           // 한글종목명
    @JsonProperty("stck_prpr") String currentPrice,         // 주식현재가
    @JsonProperty("prdy_vrss_sign") String changeSign,      // 전일대비부호 (1:상한, 2:상승, 3:보합, 4:하락, 5:하한)
    @JsonProperty("prdy_vrss") String changeAmount,          // 전일대비
    @JsonProperty("prdy_ctrt") String changeRate,            // 전일대비율
    @JsonProperty("acml_vol") String volume,                // 누적거래량
    @JsonProperty("acml_tr_pbmn") String amount,             // 누적거래대금
    @JsonProperty("hts_avls") String marketCap,             // 시가총액
    @JsonProperty("per") String per,                         // 주가수익비율(PER)
    @JsonProperty("eps") String eps,                         // 주당순이익(EPS)
    @JsonProperty("pbr") String pbr,                         // 주가순자산비율(PBR)
    @JsonProperty("stck_fcam") String faceValue,             // 액면가
    @JsonProperty("stck_hgpr") String highPrice,             // 최고가
    @JsonProperty("stck_lwpr") String lowPrice,             // 최저가
    @JsonProperty("stck_oprc") String openPrice,              // 시가
    @JsonProperty("stck_prdy_clpr") String previousClosePrice, // 전일종가
    @JsonProperty("stck_mxpr") String maxPrice,             // 최대가
    @JsonProperty("stck_llam") String minPrice,              // 최소가
    @JsonProperty("stck_sdpr") String standardPrice,        // 기준가
    @JsonProperty("stck_sspr") String settlementPrice,       // 거래정지가
    @JsonProperty("frgn_ntby_qty") String foreignNetBuy,    // 외국인순매수
    @JsonProperty("orgn_ntby_qty") String institutionNetBuy // 기관순매수
) {}

