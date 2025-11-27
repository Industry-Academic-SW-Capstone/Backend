package grit.stockIt.domain.mission.enums;

public enum MissionConditionType {
    // 1. 기본 거래/로그인 조건
    LOGIN_COUNT,                // (101: 출석체크)
    TRADE_COUNT,                // (102, 201, 203: 매수/매도 횟수)
    BUY_COUNT,
    SELL_COUNT,
    BUY_AMOUNT,
    SELL_AMOUNT,

    // [기존] 수익 관련
    PROFIT_RATE,                // (302, 304, 402, 404: 수익률 n% 달성)
    PROFIT_AMOUNT,
    TOTAL_ASSET,

    // 2. 교양 미션용
    VIEW_REPORT,                // (103: 종목 리포트 3개 확인)
    ANALYZE_PORTFOLIO,          // (104: 포트폴리오 1회 분석)

    // 3. 트랙 미션용 (확장됨)
    HOLDING_DAYS,               // (301, 303, 401, 403: 보유 기간)
    TOTAL_TRADE_AMOUNT,         // [신규] (202: 누적 거래금액)
    DAILY_PROFIT_COUNT,         // [신규] (204: 하루 익절 횟수)

    // 4. 업적 미션용
    TOTAL_MISSION_COUNT,        // (901: 주식의 달인)
    FIRST_PROFIT,               // (902: 달콤한 첫입)
    JUNK_STOCK_JACKPOT,  // (903: 강형욱 - 잡주 수익률)
    DAILY_TRADE_COUNT,          // (904: 카이팅 장인 - 하루 50회)
    HOLD_FOR_DAYS_AND_SELL_PROFIT, // (905: 존버는 승리한다)
    LOGIN_STREAK,               // (906, 907, 908: 출석 7/15/30일)
    RANKING_TOP_10,             // (909: 랭커)
    ASSET_UNDER_THRESHOLD,       // [신규] (911: 인생 2회차 - 잔고 5만원 미만)

    ACTIVITY_SCORE, // [신규] 활동 점수 누적용
    SKILL_SCORE,    // [신규] 실력 점수(수익금) 누적용
    REACH_LEGEND    // [신규] 레전드 달성 체크용 (Mission 903)
}