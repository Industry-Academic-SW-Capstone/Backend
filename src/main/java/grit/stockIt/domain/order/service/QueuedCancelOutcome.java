package grit.stockIt.domain.order.service;

public enum QueuedCancelOutcome {
    // 취소하고 홀딩을 풀었다.
    CANCELLED,
    // 그사이 체결·취소·만료로 끝났거나 없는 주문이다. 아무것도 바꾸지 않았다.
    NOT_CANCELLABLE,
    // 이미 반영한 위치다(재전달).
    DUPLICATE
}
