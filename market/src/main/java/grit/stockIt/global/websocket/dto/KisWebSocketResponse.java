package grit.stockIt.global.websocket.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS 웹소켓 응답 DTO
 */
public record KisWebSocketResponse(
        @JsonProperty("header") Header header,
        @JsonProperty("body") Body body
) {
    
    public record Header(
            @JsonProperty("tr_id") String trId,
            @JsonProperty("tr_key") String trKey,
            @JsonProperty("encrypt") String encrypt,
            @JsonProperty("datetime") String datetime
    ) {}
    
    public record Body(
            @JsonProperty("rt_cd") String rtCd,
            @JsonProperty("msg_cd") String msgCd,
            @JsonProperty("msg1") String msg1,
            @JsonProperty("output") Output output
    ) {}
    
    public record Output(
            @JsonProperty("MKSC_SHRN_ISCD") String stockCode,      // 종목코드
            @JsonProperty("STCK_PRPR") String currentPrice,        // 현재가
            @JsonProperty("PRDY_VRSS_SIGN") String changeSign,     // 전일대비부호
            @JsonProperty("PRDY_VRSS") String changeAmount,        // 전일대비
            @JsonProperty("PRDY_CTRT") String changeRate,          // 전일대비율
            @JsonProperty("ACML_VOL") String volume                // 누적거래량
    ) {}
}

