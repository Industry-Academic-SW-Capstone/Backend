package grit.stockIt.domain.account.dto;

import java.math.BigDecimal;
import java.util.List;

// 사용자 자산 조회 응답 DTO
public record AssetResponse(
        BigDecimal totalAssets,              // 총 자산
        BigDecimal cash,                      // 현금
        BigDecimal stockValue,                // 주식 자산
        List<HoldingItem> holdings           // 보유종목 목록
) {
    public record HoldingItem(
            String stockCode,           // 종목 코드
            String stockName,           // 종목명
            String marketType,          // 시장 구분 (KOSPI, KOSDAQ)
            int quantity,               // 보유 수량
            BigDecimal currentPrice,    // 현재가
            BigDecimal averagePrice,    // 평단가
            BigDecimal totalValue       // 총 평가액 (quantity × currentPrice)
    ) {}
}

