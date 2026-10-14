package grit.stockIt.global.config;

import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.stock.analysis.dto.StockAnalysisResponse;
import grit.stockIt.domain.stock.analysis.service.StockAnalysisService;
import grit.stockIt.global.jwt.JwtService;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Mono 를 돌려주는 인증 경로는 결과를 두 번째(ASYNC) 디스패치로 쓴다. JWT 필터는 그 디스패치에서 다시 돌지 않으므로
// 인가 규칙이 ASYNC 디스패치를 막으면 첫 요청은 통과해도 응답이 거부된다.
@AutoConfigureMockMvc
@DisplayName("비동기 응답의 인가")
class AsyncDispatchAuthorizationTest extends IntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MemberRepository memberRepository;

    @MockitoBean
    private StockAnalysisService stockAnalysisService;

    @Test
    @DisplayName("JWT 로 인증한 요청의 Mono 응답은 비동기 디스패치에서 막히지 않는다")
    void authenticatedMonoResponse_passesAsyncDispatch() throws Exception {
        String email = "async-" + UUID.randomUUID() + "@test.com";
        memberRepository.save(Member.builder().name("비동기").email(email).provider(AuthProvider.LOCAL).build());
        when(stockAnalysisService.scoreStock(anyString(), any(), any()))
                .thenReturn(Mono.just(new StockAnalysisResponse("005930", "삼성전자", null, null, true, null)));

        MvcResult started = mockMvc.perform(post("/api/stocks/005930/score")
                        .header("Authorization", "Bearer " + jwtService.generateToken(email)))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock_code").value("005930"));
    }
}
