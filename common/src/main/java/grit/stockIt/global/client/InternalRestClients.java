package grit.stockIt.global.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

// 서비스 사이 동기 조회용 HTTP 클라이언트. 드문 조회라 짧게 기다리고 실패를 부르는 쪽에 그대로 돌려준다 —
// 부르는 쪽이 오류 응답(파산 신청)이나 다음 기회(보유 일수 배치, 시세 기동 복구 재시도)로 처리한다.
public final class InternalRestClients {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private InternalRestClients() {
    }

    // 스프링이 만든 빌더(같은 JSON 설정)를 복제해 쓴다. 답하는 쪽과 같은 이름 규칙(SNAKE_CASE)으로 읽고 쓴다.
    public static RestClient create(RestClient.Builder builder, String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
