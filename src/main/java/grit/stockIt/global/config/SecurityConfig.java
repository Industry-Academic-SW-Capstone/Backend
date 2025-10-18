package grit.stockIt.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // 개발/REST용

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs.yaml"
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
}