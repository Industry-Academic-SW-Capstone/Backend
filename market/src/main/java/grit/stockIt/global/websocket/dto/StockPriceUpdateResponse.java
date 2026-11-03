package grit.stockIt.global.websocket.dto;

import grit.stockIt.contract.PriceChangeSign;
import java.time.LocalDateTime;

/**
 * 실시간 주식 시세 업데이트 DTO
 * 웹소켓으로 클라이언트에게 전송
 */
public record StockPriceUpdateResponse(
        String stockCode,
        String stockName,
        Integer currentPrice,
        Integer changeAmount,
        String changeRate,
        PriceChangeSign changeSign,
        Long volume,
        LocalDateTime timestamp
) {
    
    /**
     * KIS 데이터로부터 생성
     */
    public static StockPriceUpdateResponse from(
            String stockCode,
            String stockName,
            Integer currentPrice,
            Integer changeAmount,
            String changeRate,
            String changeSign,
            Long volume) {
        return new StockPriceUpdateResponse(
                stockCode,
                stockName,
                currentPrice,
                changeAmount,
                changeRate,
                PriceChangeSign.fromCode(changeSign),
                volume,
                LocalDateTime.now()
        );
    }
}

