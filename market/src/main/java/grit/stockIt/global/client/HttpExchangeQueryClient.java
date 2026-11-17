package grit.stockIt.global.client;

import grit.stockIt.contract.ExchangeQueryApi;
import grit.stockIt.contract.ExchangeQueryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;

// 시세가 따로 떠 있을 때 거래소에 HTTP 로 묻는다. 한 프로세스로 띄우면 거래소 구현이 바로 쓰이고 이 클래스는 만들어지지 않는다.
public class HttpExchangeQueryClient implements ExchangeQueryClient {

    private final RestClient restClient;

    public HttpExchangeQueryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<OpenOrder> openOrders() {
        List<OpenOrder> openOrders = restClient.get()
                .uri(ExchangeQueryApi.BASE + ExchangeQueryApi.OPEN_ORDERS)
                .retrieve()
                .body(new ParameterizedTypeReference<List<OpenOrder>>() { });
        return openOrders == null ? List.of() : openOrders;
    }
}
