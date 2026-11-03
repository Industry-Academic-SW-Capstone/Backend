package grit.stockIt.contract;

public enum ExtraEventType {
    // 미션 보상 예수금을 지급해 달라. 계좌는 증권사만 쓴다.
    REWARD_GRANTED,
    // 티어가 바뀌었다. 증권사는 회원 테이블에 복사해 랭킹에 보여 준다.
    TIER_CHANGED
}
