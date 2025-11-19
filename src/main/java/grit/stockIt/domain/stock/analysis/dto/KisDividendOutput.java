package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// KIS API 배당정보 출력 데이터 DTO
public record KisDividendOutput(
    @JsonProperty("record_date") String recordDate,
    @JsonProperty("sht_cd") String shtCd,
    @JsonProperty("isin_name") String isinName,
    @JsonProperty("divi_kind") String diviKind,
    @JsonProperty("face_val") String faceVal,
    @JsonProperty("per_sto_divi_amt") String perStoDiviAmt,
    @JsonProperty("divi_rate") String diviRate,        // 배당수익률
    @JsonProperty("stk_divi_rate") String stkDiviRate,
    @JsonProperty("divi_pay_dt") String diviPayDt,
    @JsonProperty("stk_div_pay_dt") String stkDivPayDt,
    @JsonProperty("odd_pay_dt") String oddPayDt,
    @JsonProperty("stk_kind") String stkKind,
    @JsonProperty("high_divi_gb") String highDiviGb
) {}

