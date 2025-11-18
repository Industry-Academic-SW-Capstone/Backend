package grit.stockIt.domain.stock.dto;

import java.time.LocalDateTime;
import java.util.List;

// 실시간 주식 호가창 DTO
public record StockOrderBookDto(
        String stockCode,
        String businessHour,
        String hourClassCode,
        List<OrderBookLevel> askLevels,  // 매도호가 10단계
        List<OrderBookLevel> bidLevels,  // 매수호가 10단계
        Long totalAskQuantity,           // 총 매도호가 잔량
        Long totalBidQuantity,           // 총 매수호가 잔량
        Long overtimeTotalAskQuantity,   // 시간외 총 매도호가 잔량
        Long overtimeTotalBidQuantity,   // 시간외 총 매수호가 잔량
        Integer expectedPrice,           // 예상 체결가
        Long expectedQuantity,           // 예상 체결량
        Long expectedVolume,             // 예상 거래량
        Integer expectedChangeAmount,    // 예상 체결 대비
        String expectedChangeSign,       // 예상 체결 대비 부호
        String expectedChangeRate,       // 예상 체결 전일 대비율
        Long accumulatedVolume,           // 누적 거래량
        Integer totalAskQuantityChange,   // 총 매도호가 잔량 증감
        Integer totalBidQuantityChange,   // 총 매수호가 잔량 증감
        Integer overtimeTotalAskChange,  // 시간외 총 매도호가 증감
        Integer overtimeTotalBidChange,   // 시간외 총 매수호가 증감
        LocalDateTime timestamp
) {
    // 호가 단계 정보
    public record OrderBookLevel(
            Integer price,
            Long quantity
    ) {}
    
    // KIS 실시간 호가 데이터로부터 생성
    public static StockOrderBookDto from(String[] dataFields) {
        String stockCode = dataFields[0];
        String businessHour = dataFields.length > 1 ? dataFields[1] : "";
        String hourClassCode = dataFields.length > 2 ? dataFields[2] : "";
        
        // 매도호가 10단계 (ASKP1~10: 인덱스 3~12)
        List<OrderBookLevel> askLevels = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int priceIndex = 3 + i;
            int quantityIndex = 23 + i; // ASKP_RSQN1~10: 인덱스 23~32
            if (dataFields.length > priceIndex && dataFields.length > quantityIndex) {
                Integer price = parseIntValue(dataFields[priceIndex]);
                Long quantity = parseLongValue(dataFields[quantityIndex]);
                askLevels.add(new OrderBookLevel(price, quantity));
            }
        }
        
        // 매수호가 10단계 (BIDP1~10: 인덱스 13~22)
        List<OrderBookLevel> bidLevels = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int priceIndex = 13 + i;
            int quantityIndex = 33 + i; // BIDP_RSQN1~10: 인덱스 33~42
            if (dataFields.length > priceIndex && dataFields.length > quantityIndex) {
                Integer price = parseIntValue(dataFields[priceIndex]);
                Long quantity = parseLongValue(dataFields[quantityIndex]);
                bidLevels.add(new OrderBookLevel(price, quantity));
            }
        }
        
        return new StockOrderBookDto(
                stockCode,
                businessHour,
                hourClassCode,
                askLevels,
                bidLevels,
                dataFields.length > 43 ? parseLongValue(dataFields[43]) : 0L,  // TOTAL_ASKP_RSQN
                dataFields.length > 44 ? parseLongValue(dataFields[44]) : 0L,  // TOTAL_BIDP_RSQN
                dataFields.length > 45 ? parseLongValue(dataFields[45]) : 0L,  // OVTM_TOTAL_ASKP_RSQN
                dataFields.length > 46 ? parseLongValue(dataFields[46]) : 0L,  // OVTM_TOTAL_BIDP_RSQN
                dataFields.length > 47 ? parseIntValue(dataFields[47]) : null,  // ANTC_CNPR
                dataFields.length > 48 ? parseLongValue(dataFields[48]) : null, // ANTC_CNQN
                dataFields.length > 49 ? parseLongValue(dataFields[49]) : null, // ANTC_VOL
                dataFields.length > 50 ? parseIntValue(dataFields[50]) : null,  // ANTC_CNTG_VRSS
                dataFields.length > 51 ? dataFields[51] : null,                 // ANTC_CNTG_VRSS_SIGN
                dataFields.length > 52 ? dataFields[52] : null,                 // ANTC_CNTG_PRDY_CTRT
                dataFields.length > 53 ? parseLongValue(dataFields[53]) : 0L,  // ACML_VOL
                dataFields.length > 54 ? parseIntValue(dataFields[54]) : null,  // TOTAL_ASKP_RSQN_ICDC
                dataFields.length > 55 ? parseIntValue(dataFields[55]) : null, // TOTAL_BIDP_RSQN_ICDC
                dataFields.length > 56 ? parseIntValue(dataFields[56]) : null,  // OVTM_TOTAL_ASKP_ICDC
                dataFields.length > 57 ? parseIntValue(dataFields[57]) : null,  // OVTM_TOTAL_BIDP_ICDC
                LocalDateTime.now()
        );
    }
    
    private static Integer parseIntValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private static Long parseLongValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

