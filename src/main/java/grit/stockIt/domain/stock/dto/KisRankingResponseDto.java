package grit.stockIt.domain.stock.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * KIS API 거래량/거래대금 순위 응답 DTO
 */
public record KisRankingResponseDto(
    @JsonProperty("rt_cd") String rtCd,           // 응답코드
    @JsonProperty("msg_cd") String msgCd,        // 메시지코드
    @JsonProperty("msg1") String msg1,           // 메시지
    @JsonProperty("output") Object output        // 실제 데이터 (동적으로 처리)
) {}
