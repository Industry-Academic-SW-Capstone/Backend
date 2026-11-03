package grit.stockIt.contract;

public enum MemberEventType {
    // 주문이 전부 체결돼 정산까지 끝났다.
    TRADE_COMPLETED,
    // 체결 한 건이 정산됐다.
    EXECUTION_FILLED,
    MEMBER_REGISTERED,
    // 랭킹 상위 10 위에 들었다.
    RANKER_ACHIEVED,
    STOCK_ANALYZED,
    PORTFOLIO_ANALYZED
}
