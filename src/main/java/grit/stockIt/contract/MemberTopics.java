package grit.stockIt.contract;

public final class MemberTopics {

    // 증권사가 회원에게 일어난 일을 부가 서비스(미션·알림)에 알리는 통로. 회원 ID 가 키다 — 한 회원의 이벤트는 차례로 처리된다.
    public static final String EVENTS = "member.events";

    private MemberTopics() {
    }
}
