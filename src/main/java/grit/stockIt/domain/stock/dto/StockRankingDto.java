package grit.stockIt.domain.stock.dto;

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
public record StockRankingDto(
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
     * 등락 부호 enum
     */
    public enum PriceChangeSign {
        UPPER_LIMIT("1", "상한가"),
        RISE("2", "상승"),
        STEADY("3", "보합"),
        LOWER_LIMIT("4", "하한가"),
        FALL("5", "하락");
        
        private final String code;
        private final String description;
        
        PriceChangeSign(String code, String description) {
            this.code = code;
            this.description = description;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getDescription() {
            return description;
        }
        
        /**
         * KIS API 코드로부터 PriceChangeSign 변환
         */
        public static PriceChangeSign fromCode(String code) {
            if (code == null) return STEADY;
            
            for (PriceChangeSign sign : values()) {
                if (sign.code.equals(code)) {
                    return sign;
                }
            }
            return STEADY;
        }
    }
}
