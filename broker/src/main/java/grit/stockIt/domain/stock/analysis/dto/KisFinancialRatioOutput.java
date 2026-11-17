package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// KIS API 재무비율 출력 데이터 DTO
public record KisFinancialRatioOutput(
    @JsonProperty("stac_yymm") String stacYymm,
    @JsonProperty("grs") String grs,
    @JsonProperty("bsop_prfi_inrt") String bsopPrfiInrt,
    @JsonProperty("ntin_inrt") String ntinInrt,
    @JsonProperty("roe_val") String roeVal,        // ROE
    @JsonProperty("eps") String eps,
    @JsonProperty("sps") String sps,
    @JsonProperty("bps") String bps,
    @JsonProperty("rsrv_rate") String rsrvRate,
    @JsonProperty("lblt_rate") String lbltRate     // 부채비율
) {}

