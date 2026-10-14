package grit.stockIt.global.auth;

import grit.stockIt.global.auth.entity.KisCredential;
import grit.stockIt.global.auth.entity.KisCredentialType;
import grit.stockIt.global.auth.repository.KisCredentialRepository;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class KisTokenManager {

    private final KisCredentialRepository kisCredentialRepository;
    private final WebClient webClient;
    private final KisApiProperties properties;

    // 매 KIS 호출마다 DB 를 읽지 않도록 메모리에 두고, DB 는 재시작 뒤 재발급을 피하는 데만 쓴다.
    private final Map<KisCredentialType, KisCredential> memory = new ConcurrentHashMap<>();

    // KIS가 정한 토큰 유효시간 (24시간) - 5분 여유
    private static final Duration TOKEN_VALIDITY = Duration.ofHours(24).minusMinutes(5);

    public String getAccessToken() {
        return resolve(KisCredentialType.ACCESS_TOKEN, this::fetchNewAccessToken);
    }

    public String refreshAccessToken() {
        log.info("Access Token 갱신 요청 - 기존 토큰 무효화 후 새 토큰 발급");
        return reissue(KisCredentialType.ACCESS_TOKEN, this::fetchNewAccessToken);
    }

    public String getApprovalKey() {
        return resolve(KisCredentialType.APPROVAL_KEY, this::fetchNewApprovalKey);
    }

    public String refreshApprovalKey() {
        log.info("Approval Key 갱신 요청 - 기존 키 무효화 후 새 키 발급");
        return reissue(KisCredentialType.APPROVAL_KEY, this::fetchNewApprovalKey);
    }

    public boolean hasValid(KisCredentialType type) {
        return findValid(type).isPresent();
    }

    public synchronized boolean delete(KisCredentialType type) {
        boolean inMemory = memory.remove(type) != null;
        boolean inDb = kisCredentialRepository.existsById(type);
        if (inDb) {
            kisCredentialRepository.deleteById(type);
        }
        return inMemory || inDb;
    }

    private String resolve(KisCredentialType type, Supplier<String> issuer) {
        KisCredential cached = memory.get(type);
        if (cached != null && cached.isValidAt(LocalDateTime.now())) {
            return cached.getValue();
        }
        // 동시에 들어온 요청이 각자 발급받으면 KIS 발급 빈도 제한에 걸린다.
        synchronized (this) {
            Optional<KisCredential> valid = findValid(type);
            if (valid.isPresent()) {
                memory.put(type, valid.get());
                return valid.get().getValue();
            }
            return store(type, issuer.get());
        }
    }

    private synchronized String reissue(KisCredentialType type, Supplier<String> issuer) {
        memory.remove(type);
        return store(type, issuer.get());
    }

    private Optional<KisCredential> findValid(KisCredentialType type) {
        LocalDateTime now = LocalDateTime.now();
        KisCredential cached = memory.get(type);
        if (cached != null && cached.isValidAt(now)) {
            return Optional.of(cached);
        }
        return kisCredentialRepository.findById(type).filter(credential -> credential.isValidAt(now));
    }

    private String store(KisCredentialType type, String value) {
        KisCredential credential = KisCredential.issued(type, value, LocalDateTime.now().plus(TOKEN_VALIDITY));
        kisCredentialRepository.save(credential);
        memory.put(type, credential);
        return value;
    }

    private String fetchNewAccessToken() {
        log.info("KIS에서 새 Access Token을 발급받습니다.");

        Map<String, String> requestBody = Map.of(
                "grant_type", "client_credentials",
                "appkey", properties.appkey(),
                "appsecret", properties.appsecret()
        );

        // KIS API 서버에 비동기 POST 요청
        Mono<Map<String, Object>> responseMono = webClient.post()
                .uri(properties.url() + "/oauth2/tokenP") // 토큰 발급 URL
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {}); // 응답을 Map으로 받음

        // 24시간중 한번 받기 때문에 비동기로 받을 필요가 없어서 동기적으로 받음
        Map<String, Object> response = responseMono.block();

        String newToken = (String) Objects.requireNonNull(response).get("access_token");
        log.info("새 Access Token 발급 성공");

        return newToken;
    }

    private String fetchNewApprovalKey() {
        log.info("KIS에서 새 Approval Key를 발급받습니다.");

        Map<String, String> requestBody = Map.of(
                "grant_type", "client_credentials",
                "appkey", properties.appkey(),
                "secretkey", properties.appsecret()
        );

        Mono<Map<String, Object>> responseMono = webClient.post()
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
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});

        Map<String, Object> response = responseMono.block();

        String newKey = (String) Objects.requireNonNull(response).get("approval_key");
        log.info("새 Approval Key 발급 성공");

        return newKey;
    }
}