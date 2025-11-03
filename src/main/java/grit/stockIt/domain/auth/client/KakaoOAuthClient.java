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

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoOAuthClient {

    private final WebClient webClient;
    private final KakaoOAuthProperties kakaoOAuthProperties;

    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String USER_INFO_URL = "https://kapi.kakao.com/v2/user/me";
    private static final String LOGOUT_URL = "https://kapi.kakao.com/v1/user/logout";

    /**
     * 인가 코드로 카카오 액세스 토큰 요청
     */
    public KakaoTokenResponse getAccessToken(String code) {
        return webClient.post()
                .uri(TOKEN_URL)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(org.springframework.web.reactive.function.BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("client_id", kakaoOAuthProperties.getRestApiKey())
                        .with("redirect_uri", kakaoOAuthProperties.getRedirectUri())
                        .with("code", code))
                .retrieve()
                .bodyToMono(KakaoTokenResponse.class)
                .block();
    }

    /**
     * 액세스 토큰으로 카카오 사용자 정보 조회
     */
    public KakaoUserInfoResponse getUserInfo(String accessToken) {
        return webClient.get()
                .uri(USER_INFO_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(KakaoUserInfoResponse.class)
                .block();
    }

    /**
     * 카카오 로그아웃
     */
    public void logout(String accessToken) {
        webClient.post()
                .uri(LOGOUT_URL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
        
        log.info("카카오 로그아웃 완료");
    }
}