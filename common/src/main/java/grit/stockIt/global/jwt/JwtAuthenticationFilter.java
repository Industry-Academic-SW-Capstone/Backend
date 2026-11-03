package grit.stockIt.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import grit.stockIt.global.auth.AdminEmailAllowlist;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

// JWT 인증 필터.
//
// 스프링 빈으로 등록하지 않는다. 빈이 되면 서블릿 필터로 자동 등록되어 시큐리티 체인
// 밖에서 한 번 더 도는데, 그 실행은 인가 판정 시점보다 늦어 아무 효과가 없다.
// SecurityConfig 가 직접 생성해 체인에 넣는다.
//
// 토큰을 세울 수 없으면 인증 없이 다음 필터로 넘긴다. 거절은 이 필터가 아니라
// 인가 규칙과 AuthenticationEntryPoint 가 담당한다.
//
// 회원 테이블을 읽지 않는다. 토큰의 회원 번호·이메일로 LoginMember 를 세운다 — 서비스마다 같은 서명 키로 검증만 한다.
// 그래서 탈퇴한 회원의 토큰도 만료까지는 통과한다(그 회원의 데이터가 없어 각 API 가 거절한다).
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final JwtService jwtService;
    private final AdminEmailAllowlist adminEmailAllowlist;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String jwt = authHeader.substring(BEARER_PREFIX.length());
        authenticate(jwt, request);

        filterChain.doFilter(request, response);
    }

    private void authenticate(String jwt, HttpServletRequest request) {
        if (isAlreadyAuthenticated()) {
            return;
        }

        LoginMember loginMember;
        try {
            loginMember = loginMemberOf(jwtService.parseClaims(jwt));
        } catch (Exception e) {
            log.warn("유효하지 않은 JWT 토큰: {}", e.getMessage());
            return;
        }
        if (loginMember == null) {
            // 회원 번호가 없는 예전 토큰. 다시 로그인하면 새 토큰을 받는다.
            log.warn("회원 번호가 없는 JWT 토큰");
            return;
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                loginMember,
                null, // 비밀번호는 JWT에서 검증했으므로 null
                loginMember.getAuthorities()
        );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private LoginMember loginMemberOf(Claims claims) {
        String email = claims.getSubject();
        Number memberId = claims.get(JwtService.MEMBER_ID_CLAIM, Number.class);
        if (email == null || memberId == null) {
            return null;
        }
        List<GrantedAuthority> authorities = adminEmailAllowlist.isAdmin(email)
                ? List.of(new SimpleGrantedAuthority(ADMIN_ROLE))
                : List.of();
        return new LoginMember(memberId.longValue(), email, authorities);
    }

    private boolean isAlreadyAuthenticated() {
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        return existingAuth != null && !(existingAuth instanceof AnonymousAuthenticationToken);
    }
}
