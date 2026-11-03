package grit.stockIt.contract;

import grit.stockIt.domain.order.entity.OrderMethod;

import java.math.BigDecimal;
import java.util.UUID;

// member.events 메시지. 받는 쪽이 증권사 테이블을 다시 읽지 않도록 필요한 내용을 싣는다. 종류마다 채우는 부분이 다르다.
// eventId 는 받는 쪽이 같은 이벤트를 두 번 처리하지 않게 거르는 키다. 필드는 추가만 한다.
// email 은 예전 분석 이벤트가 쓰던 필드다. 이제 모든 이벤트가 memberId 를 싣는다(필드는 지우지 않는다).
public record MemberEvent(
        String eventId,
        MemberEventType type,
        Long memberId,
        String email,
        long occurredAt,
        TradeCompleted tradeCompleted,
        ExecutionFilled executionFilled
) {

    // 주문이 전부 체결됐을 때. defaultAccount 는 기본 계좌 거래인지 — 미션은 기본 계좌 거래만 센다.
    public record TradeCompleted(Long accountId, boolean defaultAccount, String stockCode, OrderMethod orderMethod,
                                 int filledQuantity, BigDecimal filledPrice, BigDecimal buyAveragePrice) {
    }

    // 체결 한 건이 정산됐을 때. 알림 문구와 상세에 쓰는 내용이다.
    public record ExecutionFilled(Long executionId, Long orderId, Long accountId, Long contestId, String contestName,
                                  String stockCode, String stockName, BigDecimal price, Integer quantity,
                                  String orderMethod) {
    }

    public static MemberEvent tradeCompleted(Long memberId, TradeCompleted payload) {
        return new MemberEvent(newId(), MemberEventType.TRADE_COMPLETED, memberId, null, now(), payload, null);
    }

    public static MemberEvent executionFilled(Long memberId, ExecutionFilled payload) {
        return new MemberEvent(newId(), MemberEventType.EXECUTION_FILLED, memberId, null, now(), null, payload);
    }

    public static MemberEvent of(MemberEventType type, Long memberId) {
        return new MemberEvent(newId(), type, memberId, null, now(), null, null);
    }

    public static MemberEvent analyzed(MemberEventType type, Long memberId) {
        return new MemberEvent(newId(), type, memberId, null, now(), null, null);
    }

    public String key() {
        return String.valueOf(memberId);
    }

    public void validate() {
        if (eventId == null || type == null || !validForType()) {
            throw new InvalidMemberEventException("처리할 수 없는 회원 이벤트입니다. " + this);
        }
    }

    private boolean validForType() {
        return switch (type) {
            case TRADE_COMPLETED -> memberId != null && tradeCompleted != null;
            case EXECUTION_FILLED -> memberId != null && executionFilled != null;
            case MEMBER_REGISTERED, RANKER_ACHIEVED, STOCK_ANALYZED, PORTFOLIO_ANALYZED -> memberId != null;
        };
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
