package grit.stockIt.contract;

// ExchangeQueryClient 의 HTTP 형식. 거래소가 답하고 시세가 묻는다. 내부망 전용이다.
public final class ExchangeQueryApi {

    public static final String BASE = "/internal/exchange";
    public static final String OPEN_ORDERS = "/open-orders";

    private ExchangeQueryApi() {
    }
}
