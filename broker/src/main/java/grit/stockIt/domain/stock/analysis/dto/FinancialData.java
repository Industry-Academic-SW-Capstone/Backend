package grit.stockIt.domain.stock.analysis.dto;

// 재무 데이터 (ROE, 부채비율)
public record FinancialData(
    Double roe,
    Double debtRatio
) {}

