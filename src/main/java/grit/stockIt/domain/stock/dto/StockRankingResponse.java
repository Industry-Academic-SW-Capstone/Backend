package grit.stockIt.domain.stock.dto;

import grit.stockIt.contract.PriceChangeSign;

/**
 * 주식 순위 정보를 담는 DTO (가격 정보 포함)
 * @param stockCode 주식코드
 * @param stockName 주식명
 * @param volume 거래량
 * @param amount 거래대금
 * @param marketType 시장구분 (KOSPI/KOSDAQ)
 * @param currentPrice 현재가
 * @param changeAmount 전일대비 금액
 * @param changeRate 전일대비율
 * @param changeSign 등락 부호
 */
public record StockRankingResponse(
        String stockCode,
        String stockName,
        Long volume,
        Long amount,
        String marketType,

        // 가격 정보 (실시간 업데이트용)
        Integer currentPrice,
        Integer changeAmount,
        String changeRate,
        PriceChangeSign changeSign
) {

    /**
     * 시장구분만 교체한 사본을 만든다.
     *
     * KIS 응답에는 시장구분이 없어 파싱 단계에서 임시값이 들어가고, DB 조회 후 실제 값으로
     * 교체해야 한다. 9개 인자를 위치로 다시 나열하면 인접한 동일 타입(volume/amount는 Long,
     * currentPrice/changeAmount는 Integer)을 뒤바꿔 써도 컴파일되므로 그 재조립을 여기로 흡수한다.
     */
    public StockRankingResponse withMarketType(String newMarketType) {
        return new StockRankingResponse(
                stockCode,
                stockName,
                volume,
                amount,
                newMarketType,
                currentPrice,
                changeAmount,
                changeRate,
                changeSign
        );
    }
}
