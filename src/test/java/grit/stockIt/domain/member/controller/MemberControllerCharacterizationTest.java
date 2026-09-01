package grit.stockIt.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import grit.stockIt.domain.member.dto.MemberResponse;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.exception.GlobalExceptionHandler;
import grit.stockIt.global.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * [특성화 테스트 / Phase A] MemberController 관찰 동작 동결.
 *
 * 하네스 3모드:
 * - H-DIRECT   : 컨트롤러 직접 호출 → ResponseEntity 단언 (정상 경로)
 * - H-MVC-AUTH : standaloneSetup + AuthenticationPrincipalArgumentResolver + 객체 principal (인증 상태 에러 계약)
 * - H-MVC-ANON : 동일 빌더 + 인증 없음 (미인증 경로)
 *
 * standaloneSetup을 쓰는 이유는 컨트롤러 로컬 @ExceptionHandler와 GlobalExceptionHandler의
 * 우선순위가 에러 응답 계약(400 평문 vs 500 JSON)을 결정하기 때문이다.
 * @AuthenticationPrincipal 파라미터는 principal이 UserDetails가 아니면 null이 주입되므로
 * 해당 엔드포인트에는 반드시 객체 principal을 세팅한다(String principal은 미인증 재현 전용).
 *
 * 이 클래스는 MemberController와 member/account/title 도메인의 DTO·엔티티·레포지토리만 참조한다.
 */
@DisplayName("MemberController 특성화 테스트 (통합 테스트)")
class MemberControllerCharacterizationTest extends IntegrationTestSupport {

    @Autowired
    private MemberController memberController;

    @Autowired
    private MemberRepository memberRepository;

    // standaloneSetup은 애플리케이션의 메시지 컨버터 설정을 상속하지 않는다.
    // 기본 StringHttpMessageConverter는 ISO-8859-1로 써서 한글 본문이 깨지므로 실제 빈을 주입한다.
    @Autowired
    private HttpMessageConverters messageConverters;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpHarness() {
        mockMvc = MockMvcBuilders.standaloneSetup(memberController)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(messageConverters.getConverters().toArray(new HttpMessageConverter[0]))
                .build();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // 컨테이너 재사용 + 실커밋 테스트라 이메일이 충돌할 수 있어 매번 유일한 값을 쓴다.
    // member.name은 varchar(20)이므로 로컬파트를 짧게 유지한다.
    private static String uniqueEmail(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8) + "@test.com";
    }

    private Member saveMember(String email) {
        return memberRepository.save(Member.builder()
                .name(email.split("@")[0])
                .email(email)
                .provider(AuthProvider.LOCAL)
                .build());
    }

    // CustomUserDetailsService가 만드는 principal과 동형 — @AuthenticationPrincipal 엔드포인트용
    private void authenticateWithUserDetails(String email) {
        UserDetails principal = User.builder()
                .username(email)
                .password("")
                .authorities(new ArrayList<>())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    // Authentication 기반 엔드포인트(/me, PUT /me, /me/accounts, /logout, fcm-token, notification-settings)용
    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @Test
    @DisplayName("[H-DIRECT] 인증된 사용자의 내 정보 조회는 200과 본인 이메일을 반환한다")
    void getMyInfoReturnsOkWithOwnEmail() {
        String email = uniqueEmail("dir");
        saveMember(email);
        authenticateAs(email);

        ResponseEntity<MemberResponse> response = memberController.getMyInfo();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEmail()).isEqualTo(email);
    }

    @Test
    @DisplayName("[T1-02a] 중복 이메일 회원가입은 400과 평문 메시지 본문을 반환한다")
    void signupWithDuplicateEmailReturnsBadRequestWithPlainMessage() throws Exception {
        String email = uniqueEmail("dup");
        saveMember(email);

        String contentType = mockMvc.perform(post("/api/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("이미 존재하는 이메일입니다."))
                .andReturn()
                .getResponse()
                .getContentType();

        // 로컬 @ExceptionHandler가 GlobalExceptionHandler(Map→JSON)보다 우선한다는 증거
        assertThat(String.valueOf(contentType)).doesNotContain(MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("[T1-07c] 존재하지 않는 칭호로 대표 칭호를 변경하면 400을 반환한다")
    void updateRepresentativeTitleWithUnknownTitleReturnsBadRequest() throws Exception {
        String email = uniqueEmail("ttl");
        saveMember(email);
        authenticateWithUserDetails(email);

        mockMvc.perform(patch("/api/members/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("존재하지 않는 칭호입니다."));
    }

    @Test
    @DisplayName("[U-03] 미인증 상태의 대표 칭호 조회는 NPE를 거쳐 500 JSON을 반환한다")
    void getRepresentativeTitleWithoutAuthenticationReturnsInternalServerError() throws Exception {
        mockMvc.perform(get("/api/members/title"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."));
    }
}
