package grit.stockIt.domain.auth.client;

import grit.stockIt.domain.auth.config.KakaoOAuthProperties;
import grit.stockIt.domain.auth.dto.KakaoTokenResponse;
import grit.stockIt.domain.auth.dto.KakaoUserInfoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private final WebClient webClient;
    private final KakaoOAuthProperties kakaoProperties;

    /**
     * 인가 코드로 액세스 토큰 발급 (비동기)
     */
    public Mono<KakaoTokenResponse> getAccessToken(String code, String redirectUri, String state) {
        String body = "grant_type=authorization_code" +
                "&client_id=" + kakaoProperties.getRestApiKey() +
                "&redirect_uri=" + redirectUri +
                "&code=" + code;

        if (state != null) {
            body += "&state=" + state;
        }

        // client_secret이 있으면 추가
        if (kakaoProperties.getClientSecret() != null && !kakaoProperties.getClientSecret().isEmpty()) {
            body += "&client_secret=" + kakaoProperties.getClientSecret();
            log.debug("카카오 OAuth 토큰 요청에 client_secret 포함됨");
        } else {
            log.warn("카카오 OAuth 토큰 요청에 client_secret이 없습니다. kakaoProperties.getClientSecret()={}", 
                    kakaoProperties.getClientSecret());
        }

        log.info("카카오 OAuth 토큰 요청: redirectUri={}, client_id={}, client_secret_포함={}", 
                redirectUri, kakaoProperties.getRestApiKey(), 
                kakaoProperties.getClientSecret() != null && !kakaoProperties.getClientSecret().isEmpty());

        return webClient.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(), 
                        response -> {
                            log.error("카카오 OAuth 토큰 요청 실패: status={}, headers={}", 
                                    response.statusCode(), response.headers().asHttpHeaders());
                            return response.bodyToMono(String.class)
                                    .doOnNext(errorBody -> log.error("카카오 OAuth 에러 응답 본문: {}", errorBody))
                                    .then(Mono.error(new RuntimeException("카카오 OAuth 토큰 요청 실패: " + response.statusCode())));
                        })
                .bodyToMono(KakaoTokenResponse.class)
                .doOnSuccess(token -> log.info("카카오 OAuth 토큰 발급 성공"))
                .doOnError(error -> log.error("카카오 OAuth 토큰 요청 중 예외 발생", error));
    }

    /**
     * 액세스 토큰으로 사용자 정보 조회 (비동기)
     */
    public Mono<KakaoUserInfoResponse> getUserInfo(String accessToken) {
        log.info("카카오 사용자 정보 조회 요청");
        return webClient.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> {
                            log.error("카카오 사용자 정보 조회 실패: status={}", response.statusCode());
                            return response.bodyToMono(String.class)
                                    .doOnNext(errorBody -> log.error("카카오 사용자 정보 조회 에러 응답 본문: {}", errorBody))
                                    .then(Mono.error(new RuntimeException("카카오 사용자 정보 조회 실패: " + response.statusCode())));
                        })
                .bodyToMono(KakaoUserInfoResponse.class)
                .doOnSuccess(userInfo -> log.info("카카오 사용자 정보 조회 성공: email={}", 
                        userInfo.getKakaoAccount() != null ? userInfo.getKakaoAccount().getEmail() : "null"))
                .doOnError(error -> log.error("카카오 사용자 정보 조회 중 예외 발생", error));
    }

    /**
     * 카카오 로그아웃 (비동기)
     */
    public Mono<Void> logout(String accessToken) {
        return webClient.post()
                .uri("https://kapi.kakao.com/v1/user/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Void.class);
    }
}