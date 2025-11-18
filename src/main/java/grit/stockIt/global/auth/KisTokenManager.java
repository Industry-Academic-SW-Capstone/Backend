package grit.stockIt.global.auth;

import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisTokenManager {

    private final StringRedisTemplate redisTemplate;
    private final WebClient webClient;
    private final KisApiProperties properties;

    // Redis에 저장할 키 이름 (상수)
    private static final String ACCESS_TOKEN_KEY = "kis:access_token";
    private static final String APPROVAL_KEY_KEY = "kis:approval_key";

    // KIS가 정한 토큰 유효시간 (24시간) - 5분 여유
    private static final Duration TOKEN_VALIDITY = Duration.ofHours(24).minusMinutes(5);

    // 다른 서비스에서 Access Token을 요청할 때 호출
    public String getAccessToken() {
        String token = redisTemplate.opsForValue().get(ACCESS_TOKEN_KEY);
        if (token == null) {
            token = fetchNewAccessToken(); // 토큰이 없으면 KIS에 새로 요청
        }
        return token;
    }

    // KIS 인증 서버에 접속해 새 Access Token을 발급받고 Redis에 저장
    private String fetchNewAccessToken() {
        log.info("KIS에서 새 Access Token을 발급받습니다.");

        Map<String, String> requestBody = Map.of(
                "grant_type", "client_credentials",
                "appkey", properties.appkey(),
                "appsecret", properties.appsecret()
        );

        // KIS API 서버에 비동기 POST 요청
        Mono<Map> responseMono = webClient.post()
                .uri(properties.url() + "/oauth2/tokenP") // 토큰 발급 URL
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class); // 응답을 Map으로 받음

        // 24시간중 한번 받기 때문에 비동기로 받을 필요가 없어서 동기적으로 받음
        Map<String, Object> response = responseMono.block();

        String newToken = (String) Objects.requireNonNull(response).get("access_token");
        log.info("새 Access Token 발급 성공");

        // Redis에 유효시간만큼 새 토큰 저장
        redisTemplate.opsForValue().set(ACCESS_TOKEN_KEY, newToken, TOKEN_VALIDITY);

        return newToken;
    }

    // 다른 서비스에서 웹소켓 접속 요청할 때 호출
    public String getApprovalKey() {
        String key = redisTemplate.opsForValue().get(APPROVAL_KEY_KEY);
        if (key == null) {
            key = fetchNewApprovalKey(); // 키가 없으면 KIS에 새로 요청
        }
        return key;
    }

    // Approval Key를 무효화하고 새로 발급받음
    public String refreshApprovalKey() {
        log.info("Approval Key 갱신 요청 - 기존 키 무효화 후 새 키 발급");
        redisTemplate.delete(APPROVAL_KEY_KEY);
        return fetchNewApprovalKey();
    }

    // KIS 인증 서버에 접속해 새 Approval Key를 발급받고 Redis에 저장
    private String fetchNewApprovalKey() {
        log.info("KIS에서 새 Approval Key를 발급받습니다.");

        Map<String, String> requestBody = Map.of(
                "grant_type", "client_credentials",
                "appkey", properties.appkey(),
                "secretkey", properties.appsecret()
        );

        Mono<Map> responseMono = webClient.post()
                .uri(properties.url() + "/oauth2/Approval") // 웹소켓 접속키 발급 URL
                .header("Content-Type", "application/json; charset=utf-8")
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.error("KIS Approval Key 발급 실패. Status: {}, Body: {}", 
                                            response.statusCode(), body);
                                    return Mono.error(new RuntimeException("Approval Key 발급 실패: " + body));
                                }))
                .bodyToMono(Map.class);

        Map<String, Object> response = responseMono.block();

        String newKey = (String) Objects.requireNonNull(response).get("approval_key");
        log.info("새 Approval Key 발급 성공");

        redisTemplate.opsForValue().set(APPROVAL_KEY_KEY, newKey, TOKEN_VALIDITY);

        return newKey;
    }
}