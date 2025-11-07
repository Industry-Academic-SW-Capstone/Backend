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
        
        String requestURI = request.getRequestURI();
        log.info("===== JWT 필터 시작: {} =====", requestURI);
        
        // 1. Authorization 헤더에서 JWT 토큰 추출
        final String authHeader = request.getHeader("Authorization");
        log.info("Authorization 헤더: {}", authHeader);
        
        final String jwt;
        final String email;

        // Bearer 토큰이 없으면 다음 필터로 넘어감
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("Bearer 토큰이 없습니다. 인증 없이 진행합니다.");
            filterChain.doFilter(request, response);
            return;
        }

        // 2. "Bearer " 접두사 제거하고 실제 토큰 추출
        jwt = authHeader.substring(7);
        log.info("추출된 JWT 토큰: {}...", jwt.substring(0, Math.min(20, jwt.length())));
        
        try {
            // 3. 토큰에서 이메일 추출
            email = jwtService.extractEmail(jwt);
            log.info("JWT에서 추출한 이메일: {}", email);
        } catch (Exception e) {
            log.warn("JWT 토큰 파싱 중 오류 발생: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        // 4. 이메일이 있고 실제 인증이 없는 경우에만 인증 처리
        // (AnonymousAuthenticationToken은 인증되지 않은 것으로 간주)
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        boolean isNotAuthenticated = existingAuth == null || existingAuth instanceof AnonymousAuthenticationToken;
        
        if (email != null && isNotAuthenticated) {
            log.info("사용자 정보 로딩 중: {}", email);
            
            // 사용자 정보 로드
            UserDetails userDetails = this.userDetailsService.loadUserByUsername(email);
            log.info("UserDetails 로드 성공: {}", userDetails.getUsername());
            
            // 토큰 유효성 검증
            boolean isValid = jwtService.validateToken(jwt);
            log.info("JWT 토큰 유효성: {}", isValid);
            
            if (isValid) {
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
                log.info("✅ Spring Security 인증 설정 완료!");
            } else {
                log.warn("❌ JWT 토큰이 유효하지 않습니다.");
            }
        } else {
            log.info("이메일이 null이거나 이미 인증됨 - email: {}, auth: {}", 
                    email, existingAuth);
        }
        
        log.info("===== JWT 필터 종료 =====");
        // 다음 필터로 요청 전달
        filterChain.doFilter(request, response);
    }
}
