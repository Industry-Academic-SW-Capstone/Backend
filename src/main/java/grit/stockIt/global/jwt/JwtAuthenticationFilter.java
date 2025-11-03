package grit.stockIt.global.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 인증 필터
 * - HTTP 요청에서 JWT 토큰을 추출하고 인증 처리
 * - Authorization: Bearer <token> 헤더에서 토큰 추출
 * - 토큰 유효성 검증 후 Spring Security 컨텍스트에 인증 정보 설정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    /**
     * JWT 인증 필터 메인 로직
     * 1. Authorization 헤더에서 Bearer 토큰 추출
     * 2. 토큰에서 이메일 추출
     * 3. 토큰 유효성 검증
     * 4. Spring Security 컨텍스트에 인증 정보 설정
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        
        // 1. Authorization 헤더에서 JWT 토큰 추출
        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String email;

        // Bearer 토큰이 없으면 다음 필터로 넘어감
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 2. "Bearer " 접두사 제거하고 실제 토큰 추출
        jwt = authHeader.substring(7);
        try {
            // 3. 토큰에서 이메일 추출
            email = jwtService.extractEmail(jwt);
        } catch (Exception e) {
            log.warn("JWT 토큰 파싱 중 오류 발생: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 이메일이 있고 아직 인증되지 않은 경우에만 인증 처리
        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // 사용자 정보 로드
            UserDetails userDetails = this.userDetailsService.loadUserByUsername(email);
            
            // 토큰 유효성 검증
            if (jwtService.validateToken(jwt)) {
                // Spring Security 인증 토큰 생성
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null, // 비밀번호는 JWT에서 검증했으므로 null
                        userDetails.getAuthorities() // 사용자 권한 설정
                );
                
                // 요청 세부 정보 설정
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );
                
                // Spring Security 컨텍스트에 인증 정보 설정
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        
        // 다음 필터로 요청 전달
        filterChain.doFilter(request, response);
    }
}
