package grit.stockIt.global.jwt;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * JWT 토큰 정보를 담는 DTO
 * - Access Token만 포함 (Refresh Token 제외)
 * - JSON 응답 시 snake_case로 변환 (access_token)
 */
@Builder
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class JwtToken {
    private String accessToken; // JWT Access Token
}
