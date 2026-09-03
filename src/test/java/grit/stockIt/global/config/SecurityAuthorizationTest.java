package grit.stockIt.global.config;

import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인가 매트릭스 검증 — 어떤 경로가 토큰 없이 열려 있고 어떤 경로가 막히는지 고정한다.
 *
 * <p>공개 경로는 200이 아니라 "401이 아님"으로 검증한다. 외부 KIS API나 DB 상태에 따라
 * 4xx/5xx가 날 수 있지만, 그건 시큐리티 체인을 통과했다는 뜻이므로 이 테스트의 관심사가 아니다.
 */
@AutoConfigureMockMvc
@DisplayName("시큐리티 인가 규칙")
class SecurityAuthorizationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    private void assertPassesSecurityChain(MvcResult result) {
        assertThat(result.getResponse().getStatus())
                .as("시큐리티 체인을 통과해야 한다 (401/403이 아니어야 한다)")
                .isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Nested
    @DisplayName("비로그인 공개 경로")
    class PublicPaths {

        @Test
        @DisplayName("종목 검색은 토큰 없이 접근할 수 있다")
        void stockSearch_isPublic() throws Exception {
            assertPassesSecurityChain(
                    mockMvc.perform(get("/api/stocks/search").param("keyword", "삼성")).andReturn());
        }

        @Test
        @DisplayName("메인 랭킹은 토큰 없이 접근할 수 있다")
        void mainRankings_isPublic() throws Exception {
            assertPassesSecurityChain(mockMvc.perform(get("/api/rankings/main")).andReturn());
        }

        @Test
        @DisplayName("종목 상세는 토큰 없이 접근할 수 있다")
        void stockDetail_isPublic() throws Exception {
            assertPassesSecurityChain(mockMvc.perform(get("/api/stocks/005930")).andReturn());
        }

        @Test
        @DisplayName("종목 차트는 토큰 없이 접근할 수 있다")
        void stockChart_isPublic() throws Exception {
            assertPassesSecurityChain(mockMvc.perform(get("/api/stocks/005930/chart")).andReturn());
        }

        @Test
        @DisplayName("로그인·회원가입은 토큰 없이 접근할 수 있다")
        void authEndpoints_arePublic() throws Exception {
            assertPassesSecurityChain(mockMvc.perform(get("/api/members/exists").param("email", "a@b.c")).andReturn());
        }

        @Test
        @DisplayName("프로메테우스 스크레이프 경로는 시큐리티에 막히지 않는다")
        void actuatorPrometheus_isNotBlockedBySecurity() throws Exception {
            // 테스트 프로파일에는 management 블록이 없어 엔드포인트 자체가 노출되지 않는다.
            // 여기서 확인하는 것은 permitAll 규칙이 걸리는지까지다.
            assertPassesSecurityChain(mockMvc.perform(get("/actuator/prometheus")).andReturn());
        }
    }

    @Nested
    @DisplayName("인증이 필요한 경로")
    class ProtectedPaths {

        @Test
        @DisplayName("주문 조회는 토큰이 없으면 401이다")
        void orders_require401() throws Exception {
            mockMvc.perform(get("/api/orders/1")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("내 랭킹은 /api/rankings/** 공개 규칙에 삼켜지지 않고 401이다")
        void myRanking_isNotSwallowedByPublicRankingRule() throws Exception {
            mockMvc.perform(get("/api/rankings/me")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("종목 추천은 종목코드 패턴에 걸리지 않아 401이다")
        void stockRecommend_isNotMatchedByStockCodePattern() throws Exception {
            mockMvc.perform(get("/api/stocks/recommend")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("/api/stocks 아래 새 엔드포인트가 저절로 공개되지 않는다")
        void newStockEndpoint_isNotPublicByDefault() throws Exception {
            // 공개 규칙이 종목코드(숫자·대문자 6자)만 매칭하므로, 나중에 추가되는 소문자
            // 리터럴 경로는 허용목록에 넣지 않는 한 보호된다.
            mockMvc.perform(get("/api/stocks/anything")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/stocks/005930/score")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("종목 분석(POST)은 GET 공개 규칙에 삼켜지지 않고 401이다")
        void stockAnalyze_isNotSwallowedByPublicGetRule() throws Exception {
            mockMvc.perform(post("/api/stocks/005930/analyze")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("대회 랭킹 하위의 개인 포트폴리오는 공개 규칙에 삼켜지지 않는다")
        void contestMemberPortfolio_isNotSwallowedByPublicRankingRule() throws Exception {
            // PR #188이 추가하는 경로. 아직 핸들러가 없어도 인가는 매핑보다 먼저 판정되므로
            // 401이어야 한다 — 공개로 새면 대회 참가자의 보유종목이 그대로 노출된다.
            mockMvc.perform(get("/api/rankings/contest/1/members/2/portfolio"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("미션·알림·계좌도 토큰이 없으면 401이다")
        void otherDomains_require401() throws Exception {
            mockMvc.perform(get("/api/missions/dashboard")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/contests")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("401 응답은 GlobalExceptionHandler와 같은 JSON 형태다")
        void unauthorizedBody_matchesGlobalErrorShape() throws Exception {
            mockMvc.perform(get("/api/orders/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.error").value("Unauthorized"))
                    .andExpect(jsonPath("$.message").exists())
                    .andExpect(jsonPath("$.path").value("/api/orders/1"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    @Nested
    @DisplayName("관리자 전용 경로")
    class AdminPaths {

        @Test
        @DisplayName("토큰이 없으면 401이다")
        void withoutToken_is401() throws Exception {
            mockMvc.perform(get("/api/admin/kis-tokens/status")).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/batch-jobs/update-master-files")).andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser(username = "member@stockit.test")
        @DisplayName("허용목록에 없는 로그인 사용자는 403이다")
        void nonAdminMember_is403() throws Exception {
            mockMvc.perform(get("/api/admin/kis-tokens/status")).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/notifications/broadcast")).andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(username = "admin@stockit.test", roles = "ADMIN")
        @DisplayName("ROLE_ADMIN 보유자는 인가를 통과한다")
        void adminRole_passesAuthorization() throws Exception {
            assertPassesSecurityChain(mockMvc.perform(get("/api/admin/kis-tokens/status")).andReturn());
        }

        @Test
        @DisplayName("403 응답은 GlobalExceptionHandler와 같은 JSON 형태다")
        @WithMockUser(username = "member@stockit.test")
        void forbiddenBody_matchesGlobalErrorShape() throws Exception {
            mockMvc.perform(get("/api/admin/kis-tokens/status"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.error").value("Forbidden"))
                    .andExpect(jsonPath("$.path").value("/api/admin/kis-tokens/status"));
        }
    }
}
