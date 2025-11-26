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

        if (kakaoProperties.getClientSecret() != null && !kakaoProperties.getClientSecret().isEmpty()) {
            body += "&client_secret=" + kakaoProperties.getClientSecret();
        }

        return webClient.post()
                .uri("https://kauth.kakao.com/oauth/token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(KakaoTokenResponse.class);
    }

    /**
     * 액세스 토큰으로 사용자 정보 조회 (비동기)
     */
    public Mono<KakaoUserInfoResponse> getUserInfo(String accessToken) {
        return webClient.get()
                .uri("https://kapi.kakao.com/v2/user/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(KakaoUserInfoResponse.class);
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