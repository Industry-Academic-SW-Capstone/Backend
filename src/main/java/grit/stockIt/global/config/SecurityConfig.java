package grit.stockIt.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // 개발/REST용
                .cors(cors -> cors.configurationSource(corsConfigurationSource())) // CORS 설정 추가

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/kakao/callback",
                                "/api/auth/kakao/signup/complete",
                                "/api/members/login",
                                "/api/members/signup",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // 전부 허용 (개발용)
                        .anyRequest().permitAll()
                )

                // 로그인 화면/베이식 인증 비활성(원하면 유지)
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        // 세션 정책은 기본(IF_REQUIRED)로 두면 HttpSession 사용에 무리 없음
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // 허용할 오리진 설정 (패턴 사용 - withCredentials와 함께 사용 가능)
        configuration.setAllowedOriginPatterns(List.of("*"));
        
        // 허용할 HTTP 메서드
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        
        // 허용할 헤더
        configuration.setAllowedHeaders(List.of("*"));
        
        // 인증 정보 허용 (WebSocket에서 withCredentials 사용 시 필요)
        configuration.setAllowCredentials(true);
        
        // Preflight 요청의 캐시 시간
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}