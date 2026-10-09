package grit.stockIt.domain.settlement.queue;

public final class SettlementTopics {

    // 계좌 ID 가 키다. 한 계좌의 정산은 한 파티션에서 차례로 처리되어 계좌 행 락을 두고 다투지 않는다.
    public static final String REQUESTS = "settlement.requests";

    private SettlementTopics() {
    }
}
