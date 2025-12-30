package grit.stockIt.job.dto;

import java.time.LocalDate;

public record MstRecord(
        String stockCode,     // 단축코드 (Stock PK)
        String stockName,     // 한글명
        String securityGroup, // 증권그룹 (ST, FS 등 필터링용)
        String industryCode,  // 지수업종 대분류 코드 (Industry PK)
        String marketType,    // 시장구분 (KOSPI/KOSDAQ)
        LocalDate listingDate // 상장일
) {
}