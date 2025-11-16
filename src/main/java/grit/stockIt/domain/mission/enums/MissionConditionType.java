package grit.stockIt.domain.mission.enums;

public enum MissionConditionType {
    // 1. 기존 거래/로그인 조건 (이 값들도 있어야 합니다)
    LOGIN_COUNT,                // (101: 출석체크하기)
    TRADE_COUNT,                // (102, 201, 202: 당일 매수/매도)
    BUY_COUNT,
    SELL_COUNT,
    BUY_AMOUNT,
    SELL_AMOUNT,
    PROFIT_RATE,
    PROFIT_AMOUNT,
    TOTAL_ASSET,

    // --- ⬇️ [필수] data.sql에 맞게 이 값들 추가 ⬇️ ---

    // 2. 교양 미션용
    VIEW_REPORT,                // (103: 종목 리포트 3개 확인)
    ANALYZE_PORTFOLIO,          // (104: 포트폴리오 1회 분석)

    // 3. 트랙 미션용
    HOLDING_DAYS,               // (301, 302, 401, 402: 보유 종목 홀딩)

    // 4. 업적 미션용
    COMPLETE_ALL_TRACKS,        // (901: 모든 성향별 미션 완료)
    FIRST_PROFIT,               // (902: 최초 수익 실현)
    PROFIT_RATE_ON_STOCK_TYPE,  // (903: "잡주"로 수익률 100%)
    DAILY_TRADE_COUNT,          // (904: 하루 50회 거래)
    HOLD_FOR_DAYS_AND_SELL_PROFIT, // (905: 30일 보유 후 수익 실현)
    PROFIT_RATE_100,            // (906: 수익률 100% 달성)
    HOLD_SECTOR_STOCKS,         // (907: 금융 섹터 5종목 보유)
    LOGIN_STREAK,               // (908: 30일 연속 출석)
    RANKING_TOP_10,             // (909: 랭킹 Top 10)
    IPO_PROFIT_RATE_100;        // (910: 신규 상장주 수익률 100%)
}