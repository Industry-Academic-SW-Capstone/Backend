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

    // 회원 번호를 함께 싣는다 — 서비스마다 회원 테이블을 읽지 않고 토큰만으로 사용자를 안다.
    public static final String MEMBER_ID_CLAIM = "mid";

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

    public String generateToken(Long memberId, String email) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(email) // 토큰 주체 (이메일)
                .claim(MEMBER_ID_CLAIM, memberId)
                .issuedAt(now) // 발급 시간
                .expiration(expiryDate) // 만료 시간
                .signWith(getSigningKey()) // 서명
                .compact(); // 문자열로 변환
    }

    // 서명·만료를 검증하며 읽는다. 검증에 실패하면 예외를 던진다.
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey()) // 서명 검증
                .build()
                .parseSignedClaims(token) // 토큰 파싱
                .getPayload(); // 페이로드 추출
    }
}
