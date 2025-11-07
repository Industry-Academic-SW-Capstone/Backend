package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 차트 데이터 응답 DTO
 */
public record KisChartResponseDto(
    @JsonProperty("rt_cd") String rtCd,           // 응답코드
    @JsonProperty("msg_cd") String msgCd,        // 메시지코드
    @JsonProperty("msg1") String msg1,           // 메시지
    @JsonProperty("output1") Object output1,    // 현재가 정보 (단일 객체)
    @JsonProperty("output2") Object output2      // 차트 데이터 배열
) {}

