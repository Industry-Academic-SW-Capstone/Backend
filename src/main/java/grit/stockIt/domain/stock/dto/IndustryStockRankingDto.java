package grit.stockIt.domain.stock.dto;

import java.util.List;

/**
 * 업종별 인기 종목 정보를 담는 DTO
 * @param industryCode 업종 코드
 * @param industryName 업종명
 * @param stocks 해당 업종의 인기 종목 리스트 (거래대금 기준)
 */
public record IndustryStockRankingDto(
        String industryCode,
        String industryName,
        List<StockRankingDto> stocks
) {
}

