package grit.stockIt.domain.stock.analysis.dto;

// 시장 데이터 (시가총액, PER, PBR)
public record MarketData(
    Long marketCap,
    Double per,
    Double pbr
) {}

