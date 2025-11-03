package grit.stockIt.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 토큰 생성, 검증, 파싱을 담당하는 서비스
 */
@Service
public class JwtService {

    @Value("${jwt.secret}")
    private String secret; // JWT 서명에 사용할 비밀키

    @Value("${jwt.expiration:86400000}") // 24시간 (밀리초)
    private Long expiration; // 토큰 만료 시간

    /**
     * JWT 서명에 사용할 SecretKey 생성
     * 
     * @return HMAC SHA 키
     */
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes());
    }

    /**
     * 이메일 기반으로 JWT Access Token 생성
     * 
     * @param email 사용자 이메일
     * @return 생성된 JWT 토큰 문자열
     */
    public String generateToken(String email) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(email) // 토큰 주체 (이메일)
                .issuedAt(now) // 발급 시간
                .expiration(expiryDate) // 만료 시간
                .signWith(getSigningKey()) // 서명
                .compact(); // 문자열로 변환
    }

    /**
     * JWT 토큰에서 이메일 추출
     * 
     * @param token JWT 토큰 문자열
     * @return 추출된 이메일
     */
    public String extractEmail(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey()) // 서명 검증
                .build()
                .parseSignedClaims(token) // 토큰 파싱
                .getPayload(); // 페이로드 추출
        return claims.getSubject(); // 이메일 반환
    }

    /**
     * JWT 토큰 유효성 검증
     * 
     * @param token 검증할 JWT 토큰
     * @return 유효하면 true, 그렇지 않으면 false
     */
    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey()) // 서명 검증
                    .build()
                    .parseSignedClaims(token); // 토큰 파싱
            return true; // 파싱 성공 시 유효한 토큰
        } catch (Exception e) {
            return false; // 파싱 실패 시 유효하지 않은 토큰
        }
    }
}
