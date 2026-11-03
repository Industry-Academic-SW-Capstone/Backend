package grit.stockIt.domain.auth.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import grit.stockIt.global.jwt.JwtToken;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KakaoLoginResponse {
    private boolean isNewUser;
    private JwtToken token;              // 기존 유저인 경우
    private KakaoSignupResponse signupInfo; // 신규 유저인 경우

    // 기존 회원 응답 생성
    public static KakaoLoginResponse ofExistingUser(JwtToken token) {
        return KakaoLoginResponse.builder()
                .isNewUser(false)
                .token(token)
                .build();
    }

    // 신규 회원 응답 생성
    public static KakaoLoginResponse ofNewUser(KakaoSignupResponse signupInfo) {
        return KakaoLoginResponse.builder()
                .isNewUser(true)
                .signupInfo(signupInfo)
                .build();
    }
}