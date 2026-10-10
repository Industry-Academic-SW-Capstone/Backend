package grit.stockIt.domain.matching.queue;

public enum MatchingCommandType {
    FILL,
    CANCEL,
    // 시장가 주문 당일 만료. 처리는 취소와 같고, 사용자가 아니라 장 마감이 끝냈다는 것만 다르다.
    EXPIRE
}
