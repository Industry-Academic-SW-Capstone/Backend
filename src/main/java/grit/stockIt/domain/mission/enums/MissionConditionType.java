package grit.stockIt.domain.mission.enums;

/**
 * 미션 완료 조건 타입
 * (이벤트 리스너가 이 타입을 보고 미션을 갱신할지 판단합니다)
 */
public enum MissionConditionType {

    // --- 거래(TradeEvent) 관련 ---
    BUY_COUNT,          // 매수 횟수 (종목 무관)
    SELL_COUNT,         // 매도 횟수 (종목 무관)
    TRADE_COUNT,        // 총 거래 횟수 (매수 + 매도)
    BUY_AMOUNT,         // 총 매수 금액
    SELL_AMOUNT,        // 총 매도 금액
    PROFIT_RATE,        // 수익률 달성 (예: 10% 이상)
    PROFIT_AMOUNT,      // 수익금 달성 (예: 10,000원 이상)
    HOLDING_PERIOD,     // 주식 보유 기간 (예: 3일 이상 보유 후 매도)

    // --- 계좌(Account) 관련 ---
    TOTAL_ASSET,        // 총 자산 달성 (예: 2,000,000원)

    // --- 접속/기타 (LoginEvent 등 별도 이벤트 필요) ---
    LOGIN_COUNT,        // 접속 횟수 (출석 체크용)

    // --- 미션 완료(MissionCompletionEvent) 관련 (하드코딩 방식에서는 불필요할 수 있음) ---
    // COMPLETE_DAILY_MISSION_COUNT, // 일일 미션 완료 횟수 (출석 7일 달성용)
    // COMPLETE_TRACK_COUNT        // 특정 트랙 완료 횟수
}