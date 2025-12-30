package grit.stockIt.domain.mission.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 종목 분석 완료 이벤트
 */
@Getter
@RequiredArgsConstructor
public class StockAnalyzedEvent {
    private final String email;      // 미션 수행자
    private final String stockCode;  // 분석한 종목
}