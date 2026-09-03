package grit.stockIt.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 인증 필터
 * - HTTP 요청에서 JWT 토큰을 추출하고 인증 처리
 * - Authorization: Bearer &lt;token&gt; 헤더에서 토큰 추출
 * - 토큰 유효성 검증 후 Spring Security 컨텍스트에 인증 정보 설정
 *
 * <p>스프링 빈으로 등록하지 않는다. 빈이 되면 서블릿 필터로 자동 등록되어 시큐리티 체인
 * 밖에서 한 번 더 도는데, 그 실행은 인가 판정 시점보다 늦어 아무 효과가 없다.
 * {@code SecurityConfig}가 직접 생성해 체인에 넣는다.
 *
 * <p>토큰을 세울 수 없으면 인증 없이 다음 필터로 넘긴다. 거절은 이 필터가 아니라
 * 인가 규칙과 AuthenticationEntryPoint가 담당한다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

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

        // 회원 조회 전에 서명·만료부터 검증한다 — 위조 토큰으로 DB를 때리지 않게 한다.
        if (!jwtService.validateToken(jwt)) {
            log.warn("유효하지 않은 JWT 토큰");
            return;
        }

        String email;
        try {
            email = jwtService.extractEmail(jwt);
        } catch (Exception e) {
            log.warn("JWT 토큰 파싱 실패: {}", e.getMessage());
            return;
        }
        if (email == null) {
            return;
        }

        UserDetails userDetails;
        try {
            userDetails = userDetailsService.loadUserByUsername(email);
        } catch (UsernameNotFoundException e) {
            // 탈퇴한 회원의 유효 토큰. 여기서 던지면 필터 예외라 500이 되므로 미인증으로 넘긴다.
            log.warn("JWT는 유효하나 회원이 존재하지 않습니다");
            return;
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails,
                null, // 비밀번호는 JWT에서 검증했으므로 null
                userDetails.getAuthorities()
        );
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    private boolean isAlreadyAuthenticated() {
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        return existingAuth != null && !(existingAuth instanceof AnonymousAuthenticationToken);
    }
}
