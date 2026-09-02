package grit.stockIt.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.global.jwt.JwtAuthenticationFilter;
import grit.stockIt.global.jwt.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class SecurityConfig {

    /**
     * 메서드 구분 없이 인증 없이 열어두는 경로.
     *
     * <p>{@code /ws/**}: SockJS 핸드셰이크는 Authorization 헤더를 실을 수 없다. 현재 STOMP로
     * 나가는 것은 {@code /topic/stock/**} 공개 시세뿐이라 열어두어도 사용자 데이터가 새지 않는다.
     * 사용자별 목적지({@code /queue/**})를 쓰게 되면 CONNECT 프레임을 검사하는
     * ChannelInterceptor가 필요하다.
     *
     * <p>{@code /actuator/**}: Prometheus가 도커 네트워크 안에서 토큰 없이 스크레이프한다
     * (monitoring/prometheus/prometheus.yml). 어떤 엔드포인트를 여는지는 application.yml의
     * management.endpoints.web.exposure.include로 좁힌다.
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/members/login",
            "/api/members/signup",
            "/api/members/exists",
            "/api/auth/kakao/callback",
            "/api/auth/kakao/signup/complete",
            "/ws/**",
            "/actuator/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/",
            "/error"
    };

    /**
     * 로그인 전 온보딩 화면이 쓰는 읽기 전용 시세·랭킹. GET에만 적용한다.
     *
     * <p>{@code /api/stocks/*}가 {@code /api/stocks/recommend}까지 삼키므로, 인증이 필요한
     * 경로를 이 목록보다 먼저 매칭시켜야 한다 — {@link #filterChain} 참고.
     */
    private static final String[] PUBLIC_GET_PATHS = {
            "/api/stocks/search",
            "/api/stocks/amount",
            "/api/stocks/fluctuations",
            "/api/stocks/industries",
            "/api/stocks/*",
            "/api/stocks/*/chart",
            "/api/rankings/main",
            // '*'는 한 세그먼트만 매칭한다. '**'로 열면 그 아래 개인 데이터까지 공개된다
            // — 예: /contest/{id}/members/{id}/portfolio (대회 참가자 보유종목, PR #188).
            "/api/rankings/contest/*"
    };

    /** 관리자 전용 경로. 운영 데이터를 바꾸거나 외부 API 토큰을 다루는 것들이다. */
    private static final String[] ADMIN_PATHS = {
            "/api/admin/**",
            "/api/batch-jobs/**"
    };

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;
    private final List<String> allowedOrigins;

    public SecurityConfig(
            JwtService jwtService,
            UserDetailsService userDetailsService,
            ObjectMapper objectMapper,
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins
    ) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
        this.allowedOrigins = allowedOrigins;

        // 빈 값은 모든 오리진 차단이라 프론트가 통째로 죽는다. 배포 환경에서 환경변수가
        // 비면 yml 기본값을 덮어써 버리므로, 조용히 굴러가지 말고 기동에서 멈춘다.
        if (allowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "app.cors.allowed-origins가 비어 있습니다. APP_CORS_ALLOWED_ORIGINS를 설정하세요 "
                            + "(예: https://stockit.example.com — 스킴 포함, 끝에 슬래시 없이).");
        }
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // JWT 인증이라 서버 세션을 만들지 않는다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // ROLE_ADMIN은 app.admin.emails 허용목록에서 나온다 — AdminEmailAllowlist 참고.
                        .requestMatchers(ADMIN_PATHS).hasRole("ADMIN")

                        // PUBLIC_GET_PATHS의 와일드카드에 삼켜지지 않도록 먼저 선언한다.
                        .requestMatchers(HttpMethod.GET, "/api/stocks/recommend").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/rankings/me").authenticated()

                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .anyRequest().authenticated()
                )

                // 필터가 시큐리티 체인 안에 있어야 인가 판정 시점에 SecurityContext가 채워져 있다.
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtService, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class
                )

                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                )

                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    /**
     * 시큐리티가 거절한 요청도 GlobalExceptionHandler와 같은 JSON 형태로 돌려준다.
     * 형태가 갈리면 프론트가 401/403만 따로 처리해야 한다.
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) ->
                writeError(response, request, HttpStatus.UNAUTHORIZED, "Unauthorized", "로그인이 필요합니다.");
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                writeError(response, request, HttpStatus.FORBIDDEN, "Forbidden", "접근 권한이 없습니다.");
    }

    private void writeError(
            HttpServletResponse response,
            HttpServletRequest request,
            HttpStatus status,
            String error,
            String message
    ) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // allowCredentials(true)와 와일드카드 오리진은 함께 쓸 수 없다 — 도메인을 명시한다.
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
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
