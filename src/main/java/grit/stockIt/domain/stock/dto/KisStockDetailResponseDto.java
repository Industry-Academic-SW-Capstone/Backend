package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 주식현재가 시세 응답 DTO
 */
public record KisStockDetailResponseDto(
    @JsonProperty("rt_cd") String rtCd,           // 응답코드
    @JsonProperty("msg_cd") String msgCd,        // 메시지코드
    @JsonProperty("msg1") String msg1,           // 메시지
    @JsonProperty("output") Object output        // 실제 데이터 (Map 또는 List로 처리)
) {}

