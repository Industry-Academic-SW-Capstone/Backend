package grit.stockIt.global.jwt;

import grit.stockIt.global.auth.AdminEmailAllowlist;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JWT 인증 필터 — 회원 테이블 없이 토큰만으로 로그인 회원을 세운다")
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-key-that-is-long-enough-for-hmac-sha256";
    private static final String ADMIN_EMAIL = "admin@stockit.test";

    private final JwtService jwtService = new JwtService();
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwtService, "secret", SECRET);
        ReflectionTestUtils.setField(jwtService, "expiration", 60_000L);
        filter = new JwtAuthenticationFilter(jwtService, new AdminEmailAllowlist(List.of(ADMIN_EMAIL)));
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("토큰의 회원 번호·이메일로 LoginMember 를 세운다")
    void tokenWithMemberId_authenticatesLoginMember() throws Exception {
        run(jwtService.generateToken(42L, "member@stockit.test"));

        LoginMember loginMember = LoginMember.current().orElseThrow();
        assertThat(loginMember.memberId()).isEqualTo(42L);
        assertThat(loginMember.getUsername()).isEqualTo("member@stockit.test");
        assertThat(loginMember.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("관리자 허용목록의 이메일이면 ROLE_ADMIN 을 붙인다")
    void adminEmail_getsAdminRole() throws Exception {
        run(jwtService.generateToken(1L, ADMIN_EMAIL));

        assertThat(LoginMember.current().orElseThrow().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    @DisplayName("회원 번호가 없는 예전 토큰은 인증하지 않는다(다시 로그인)")
    void tokenWithoutMemberId_isNotAuthenticated() throws Exception {
        String legacy = Jwts.builder()
                .subject("member@stockit.test")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes()))
                .compact();

        run(legacy);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("서명이 다른 토큰은 인증하지 않는다")
    void forgedToken_isNotAuthenticated() throws Exception {
        String forged = Jwts.builder()
                .subject("member@stockit.test")
                .claim(JwtService.MEMBER_ID_CLAIM, 42L)
                .signWith(Keys.hmacShaKeyFor("another-secret-key-that-is-long-enough-for-hmac".getBytes()))
                .compact();

        run(forged);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void run(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
