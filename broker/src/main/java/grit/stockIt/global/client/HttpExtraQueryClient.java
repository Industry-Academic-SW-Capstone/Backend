package grit.stockIt.global.client;

import grit.stockIt.contract.ExtraQueryApi;
import grit.stockIt.contract.ExtraQueryClient;
import grit.stockIt.contract.TitleView;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

// 증권사가 따로 떠 있을 때 부가에 HTTP 로 묻는다(대표 칭호 장착·프로필). 한 프로세스로 띄우면 부가 구현이 바로 쓰인다.
public class HttpExtraQueryClient implements ExtraQueryClient {

    private static final int NOT_FOUND = 404;

    private final RestClient restClient;

    public HttpExtraQueryClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<TitleView> title(Long titleId) {
        return restClient.get()
                .uri(ExtraQueryApi.BASE + ExtraQueryApi.TITLE, titleId)
                .exchange((request, response) -> {
                    if (response.getStatusCode().value() == NOT_FOUND) {
                        return Optional.empty();
                    }
                    if (response.getStatusCode().isError()) {
                        throw new IllegalStateException("칭호 조회 실패: " + response.getStatusCode());
                    }
                    return Optional.ofNullable(response.bodyTo(TitleView.class));
                });
    }

    @Override
    public boolean ownsTitle(Long memberId, Long titleId) {
        ExtraQueryApi.Owned owned = restClient.get()
                .uri(ExtraQueryApi.BASE + ExtraQueryApi.TITLE_OWNED, memberId, titleId)
                .retrieve()
                .body(ExtraQueryApi.Owned.class);
        return owned != null && owned.owned();
    }

    @Override
    public List<TitleView> titlesOf(Long memberId) {
        List<TitleView> titles = restClient.get()
                .uri(ExtraQueryApi.BASE + ExtraQueryApi.MEMBER_TITLES, memberId)
                .retrieve()
                .body(new ParameterizedTypeReference<List<TitleView>>() { });
        return titles == null ? List.of() : titles;
    }
}
