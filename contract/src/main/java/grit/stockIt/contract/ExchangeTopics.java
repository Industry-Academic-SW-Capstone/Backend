package grit.stockIt.contract;

public final class ExchangeTopics {

    // 거래소가 주문 결과를 증권사에 알리는 통로. 계좌 ID 가 키다 — 한 계좌의 결과는 한 파티션에서 도착 순서대로 처리된다.
    public static final String REPORTS = "exchange.reports";

    private ExchangeTopics() {
    }
}
