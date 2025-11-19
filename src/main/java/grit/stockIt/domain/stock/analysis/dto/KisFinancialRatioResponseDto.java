package grit.stockIt.domain.stock.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// KIS API 재무비율 응답 DTO
public record KisFinancialRatioResponseDto(
    @JsonProperty("rt_cd") String rtCd,
    @JsonProperty("msg_cd") String msgCd,
    @JsonProperty("msg1") String msg1,
    @JsonProperty("output") List<KisFinancialRatioOutput> output
) {}

