package grit.stockIt.domain.stock.dto;

import java.time.LocalDateTime;

/**
 * 실시간 주식 시세 업데이트 DTO
 * 웹소켓으로 클라이언트에게 전송
 */
public record StockPriceUpdateDto(
        String stockCode,
        String stockName,
        Integer currentPrice,
        Integer changeAmount,
        String changeRate,
        StockRankingDto.PriceChangeSign changeSign,
        Long volume,
        LocalDateTime timestamp
) {
    
    /**
     * KIS 데이터로부터 생성
     */
    public static StockPriceUpdateDto from(
            String stockCode,
            String stockName,
            Integer currentPrice,
            Integer changeAmount,
            String changeRate,
            String changeSign,
            Long volume) {
        return new StockPriceUpdateDto(
                stockCode,
                stockName,
                currentPrice,
                changeAmount,
                changeRate,
                StockRankingDto.PriceChangeSign.fromCode(changeSign),
                volume,
                LocalDateTime.now()
        );
    }
}

