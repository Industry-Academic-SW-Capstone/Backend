package grit.stockIt.domain.stock.dto;

/**
 * 주식 순위 정보를 담는 DTO
 * @param stockCode 주식코드
 * @param stockName 주식명
 * @param volume 거래량
 * @param amount 거래대금
 * @param marketCap 시가총액
 * @param marketType 시장구분 (KOSPI/KOSDAQ)
 */
public record StockRankingDto(
        String stockCode,
        String stockName,
        Long volume,
        Long amount,
        Long marketCap,
        String marketType
) {
    
    /**
     * 거래량 상위 종목용 생성자
     */
    public static StockRankingDto forVolumeRanking(String stockCode, String stockName, Long volume, String marketType) {
        return new StockRankingDto(stockCode, stockName, volume, 0L, 0L, marketType);
    }
    
    /**
     * 거래대금 상위 종목용 생성자
     */
    public static StockRankingDto forAmountRanking(String stockCode, String stockName, Long amount, String marketType) {
        return new StockRankingDto(stockCode, stockName, 0L, amount, 0L, marketType);
    }
    
    /**
     * 시가총액 상위 종목용 생성자
     */
    public static StockRankingDto forMarketCapRanking(String stockCode, String stockName, Long marketCap, String marketType) {
        return new StockRankingDto(stockCode, stockName, 0L, 0L, marketCap, marketType);
    }
}
