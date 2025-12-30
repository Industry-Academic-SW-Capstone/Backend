package grit.stockIt.domain.stock.dto;

public record StockSearchDto(
        String stockCode,
        String stockName,
        double similarity
) {}
