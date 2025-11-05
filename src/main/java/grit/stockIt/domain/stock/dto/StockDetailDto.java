package grit.stockIt.domain.stock.dto;

/**
 * 주식 상세 정보 DTO (클라이언트 응답용)
 */
public record StockDetailDto(
    String stockCode,              // 종목코드
    String stockName,               // 종목명
    Integer currentPrice,           // 현재가
    Integer changeAmount,           // 전일대비
    String changeRate,              // 전일대비율
    StockRankingDto.PriceChangeSign changeSign, // 등락부호
    Long volume,                    // 누적거래량
    Long amount,                    // 누적거래대금
    Long marketCap,                 // 시가총액 (원)
    Double per,                     // 주가수익비율(PER)
    Double eps,                     // 주당순이익(EPS)
    Double pbr,                     // 주가순자산비율(PBR)
    Integer faceValue,              // 액면가
    Integer highPrice,              // 최고가
    Integer lowPrice,               // 최저가
    Integer openPrice,              // 시가
    Integer previousClosePrice      // 전일종가
) {}

