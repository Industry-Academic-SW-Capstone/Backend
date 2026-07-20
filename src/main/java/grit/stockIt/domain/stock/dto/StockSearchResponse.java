package grit.stockIt.domain.stock.dto;

public record StockSearchResponse(
        String stockCode,
        String stockName,
        double similarity
) {}
