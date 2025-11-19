package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// KIS API 배당정보 응답 DTO
public record KisDividendResponseDto(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output1") List<KisDividendOutput> output1
) {}

