package grit.stockIt.domain.stock.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 주식 차트 데이터 DTO (클라이언트 응답용)
 */
public record StockChartDto(
    String stockCode,              // 종목코드
    String periodType,             // 기간 타입 (day/week/month/year/minute)
    LocalDate date,                // 기준일자
    LocalTime time,                // 기준시간 (분봉일 경우만 사용, null 가능)
    Integer openPrice,             // 시가
    Integer highPrice,             // 고가
    Integer lowPrice,              // 저가
    Integer closePrice,            // 종가
    Long volume,                   // 누적거래량
    Long amount,                   // 누적거래대금
    Integer changeAmount,          // 전일대비
    String changeRate              // 전일대비율
) {}

