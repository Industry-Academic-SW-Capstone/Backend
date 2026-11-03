package grit.stockIt.global.client;

import grit.stockIt.contract.BrokerQueryApi;
import grit.stockIt.contract.BrokerQueryClient;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// 부가가 따로 떠 있을 때 증권사에 HTTP 로 묻는다. 한 프로세스로 띄우면 증권사 구현이 바로 쓰이고 이 클래스는 만들어지지 않는다.
public class HttpBrokerQueryClient implements BrokerQueryClient {

    private static final int NOT_FOUND = 404;

    private final RestClient restClient;

    public HttpBrokerQueryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<BigDecimal> defaultAccountAsset(Long memberId) {
        return restClient.get()
                .uri(BrokerQueryApi.BASE + BrokerQueryApi.DEFAULT_ACCOUNT_ASSET, memberId)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == NOT_FOUND) {
                        return Optional.empty();
                    }
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("증권사 자산 조회 실패: " + response.getStatusCode());
                    }
                    BrokerQueryApi.Asset asset = response.bodyTo(BrokerQueryApi.Asset.class);
                    return Optional.ofNullable(asset).map(BrokerQueryApi.Asset::amount);
                });
    }

    @Override
    public Set<Long> membersHoldingStock(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        BrokerQueryApi.MemberIds holders = restClient.post()
                .uri(BrokerQueryApi.BASE + BrokerQueryApi.MEMBERS_HOLDING_STOCK)
                .body(new BrokerQueryApi.MemberIds(List.copyOf(memberIds)))
                .retrieve()
                .body(BrokerQueryApi.MemberIds.class);
        return holders == null ? Set.of() : new HashSet<>(holders.memberIds());
    }

    @Override
    public List<Long> allMemberIds() {
        BrokerQueryApi.MemberIds memberIds = restClient.get()
                .uri(BrokerQueryApi.BASE + BrokerQueryApi.MEMBER_IDS)
                .retrieve()
                .body(BrokerQueryApi.MemberIds.class);
        return memberIds == null ? List.of() : memberIds.memberIds();
    }
}
