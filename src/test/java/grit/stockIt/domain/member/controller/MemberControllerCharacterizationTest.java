package grit.stockIt.domain.member.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.auth.service.KakaoAuthService;
import grit.stockIt.domain.member.dto.FcmTokenRequest;
import grit.stockIt.domain.member.dto.MemberLoginRequest;
import grit.stockIt.domain.member.dto.MemberResponse;
import grit.stockIt.domain.member.dto.MemberSignupRequest;
import grit.stockIt.domain.member.dto.MemberUpdateRequest;
import grit.stockIt.domain.member.dto.NotificationSettingsRequest;
import grit.stockIt.domain.member.dto.RepresentativeTitleResponse;
import grit.stockIt.domain.member.dto.TitleSelectRequest;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import grit.stockIt.domain.title.repository.TitleRepository;
import grit.stockIt.global.exception.GlobalExceptionHandler;
import grit.stockIt.global.jwt.JwtToken;
import grit.stockIt.global.support.IntegrationTestSupport;
import java.math.BigDecimal;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 이 클래스는 MemberController와 member/account/title 도메인의 DTO·엔티티·레포지토리,
 * 그리고 카카오 가입 경로 대조를 위한 KakaoAuthService만 참조한다.
 */
@DisplayName("MemberController 특성화 테스트 (통합 테스트)")
class MemberControllerCharacterizationTest extends IntegrationTestSupport {

    private static final String RAW_PASSWORD = "password123!";

    @Autowired
    private MemberController memberController;

    @Autowired
    private KakaoAuthService kakaoAuthService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TitleRepository titleRepository;

    @Autowired
    private MemberTitleRepository memberTitleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private Member saveMember(String email) {
        return memberRepository.save(Member.builder()
                .name(email.split("@")[0])
                .email(email)
                .provider(AuthProvider.LOCAL)
                .build());
    }

    // title.name은 unique 제약이 있고 컨테이너가 재사용되므로 테스트마다 새 이름을 만든다.
    private Title saveTitle() {
        return titleRepository.save(Title.builder()
                .name("특성화 칭호 " + uniqueSuffix())
                .description("특성화 테스트용 칭호")
                .build());
    }

    private void grantTitle(Member member, Title title) {
        memberTitleRepository.save(MemberTitle.builder()
                .member(member)
                .title(title)
                .build());
    }

    // representative_title_id는 LAZY 연관이라 세션 밖 조회를 피하고 컬럼을 직접 읽는다.
    private Long representativeTitleIdOf(Member member) {
        return jdbcTemplate.queryForObject(
                "select representative_title_id from member where member_id = ?",
                Long.class, member.getMemberId());
    }

    private String nameOf(String email) {
        return memberRepository.findByEmail(email).orElseThrow().getName();
    }

    // TitleSelectRequest는 생성자·세터가 없어 역직렬화로만 만들 수 있다.
    private TitleSelectRequest titleSelectRequest(Long titleId) throws Exception {
        return objectMapper.readValue("{\"titleId\":" + titleId + "}", TitleSelectRequest.class);
    }

    private UserDetails userDetailsPrincipal(String email) {
        // CustomUserDetailsService가 만드는 principal과 동형 — @AuthenticationPrincipal 엔드포인트용
        return User.builder()
                .username(email)
                .password("")
                .authorities(new ArrayList<>())
                .build();
    }

    private void authenticateWithUserDetails(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetailsPrincipal(email), null, List.of()));
    }

    // Authentication 기반 엔드포인트(/me, PUT /me, /me/accounts, /logout, fcm-token, notification-settings)용
    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    @Test
    @DisplayName("[T1-01a·T1-01b] 로컬 가입은 200과 기본 응답을 주고 암호화된 비밀번호·기본 계좌를 남긴다")
    void signupReturnsDefaultResponseAndPersistsEncodedPasswordWithDefaultAccount() {
        String email = uniqueEmail("s01");

        ResponseEntity<MemberResponse> response =
                memberController.signup(new MemberSignupRequest(email, RAW_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        MemberResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getEmail()).isEqualTo(email);
        assertThat(body.getName()).isEqualTo(email.split("@")[0]);
        assertThat(body.getProvider()).isEqualTo("LOCAL");
        assertThat(body.getBalance()).isNotNull();
        assertThat(body.getBalance().compareTo(BigDecimal.ZERO)).isZero();
        assertThat(body.getTitles()).isEmpty();
        assertThat(body.getRepresentativeTitleId()).isNull();

        Member saved = memberRepository.findByEmail(email).orElseThrow();
        assertThat(saved.getPassword()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.getPassword()).startsWith("$2");
        assertThat(accountRepository.findByMemberAndIsDefaultTrue(saved)).isPresent();
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
    @DisplayName("[T1-02b·T1-02c] 가입 검증 실패는 400과 첫 번째 필드 에러 메시지 하나만 반환한다")
    void signupValidationFailuresReturnFirstFieldErrorMessage() throws Exception {
        mockMvc.perform(post("/api/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("s2b") + "\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("비밀번호는 8자 이상이어야 합니다."));

        mockMvc.perform(post("/api/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"notanemail\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("올바른 이메일 형식이 아닙니다."));
    }

    @Test
    @DisplayName("[T1-03·T1-04a·T1-04b] 로그인은 토큰을 주고, 미존재 이메일·오답 비밀번호는 서로 다른 400 메시지를 준다")
    void loginReturnsTokenAndDistinctFailureMessages() throws Exception {
        String email = uniqueEmail("lgn");
        memberController.signup(new MemberSignupRequest(email, RAW_PASSWORD));

        ResponseEntity<JwtToken> response =
                memberController.login(new MemberLoginRequest(email, RAW_PASSWORD));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getAccessToken()).isNotBlank();

        mockMvc.perform(post("/api/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail("non") + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("존재하지 않는 이메일입니다."));

        mockMvc.perform(post("/api/members/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrongpassword\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("비밀번호가 일치하지 않습니다."));
    }

    @Test
    @DisplayName("[T1-05a·T1-05b·T1-05c] 내 정보 수정은 지정한 필드만 반영하고 null 필드는 무시한다")
    void updateMyInfoAppliesProvidedFieldsAndIgnoresNulls() {
        String email = uniqueEmail("u05");
        Member member = saveMember(email);
        Title title = saveTitle();
        grantTitle(member, title);
        authenticateAs(email);

        ResponseEntity<MemberResponse> updated = memberController.updateMyInfo(new MemberUpdateRequest(
                "새이름", "profile.png", true, true, true, true, true, null));

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().getName()).isEqualTo("새이름");
        assertThat(updated.getBody().getProfileImage()).isEqualTo("profile.png");
        assertThat(updated.getBody().isTwoFactorEnabled()).isTrue();
        assertThat(updated.getBody().isNotificationAgreement()).isTrue();
        assertThat(updated.getBody().isMainTutorialCompleted()).isTrue();
        assertThat(updated.getBody().isSecuritiesDepthTutorialCompleted()).isTrue();
        assertThat(updated.getBody().isStockDetailTutorialCompleted()).isTrue();

        Member reloaded = memberRepository.findByEmail(email).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("새이름");
        assertThat(reloaded.getProfileImage()).isEqualTo("profile.png");
        assertThat(reloaded.isTwoFactorEnabled()).isTrue();

        ResponseEntity<MemberResponse> untouched = memberController.updateMyInfo(
                new MemberUpdateRequest(null, null, null, null, null, null, null, null));

        assertThat(untouched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(untouched.getBody()).isNotNull();
        assertThat(untouched.getBody().getName()).isEqualTo("새이름");
        assertThat(untouched.getBody().getProfileImage()).isEqualTo("profile.png");
        assertThat(untouched.getBody().isTwoFactorEnabled()).isTrue();
        assertThat(nameOf(email)).isEqualTo("새이름");

        ResponseEntity<MemberResponse> equipped = memberController.updateMyInfo(
                new MemberUpdateRequest(null, null, null, null, null, null, null, title.getId()));

        assertThat(equipped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(equipped.getBody()).isNotNull();
        assertThat(equipped.getBody().getRepresentativeTitleId()).isEqualTo(title.getId());
        assertThat(representativeTitleIdOf(member)).isEqualTo(title.getId());
    }

    @Test
    @DisplayName("[T1-06a·T1-06b·T1-06c] 내 정보 수정의 칭호 오류는 400이며 같은 요청의 이름 변경도 남지 않는다")
    void updateMyInfoTitleErrorsReturnBadRequestAndRollBackProfileChange() throws Exception {
        String email = uniqueEmail("u06");
        saveMember(email);
        Title unownedTitle = saveTitle();
        authenticateAs(email);
        String originalName = nameOf(email);

        mockMvc.perform(put("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"representative_title_id\":999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("존재하지 않는 칭호입니다."));

        mockMvc.perform(put("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"representative_title_id\":" + unownedTitle.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("보유하지 않은 칭호는 장착할 수 없습니다."));

        mockMvc.perform(put("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"변경된이름\",\"representative_title_id\":"
                                + unownedTitle.getId() + "}"))
                .andExpect(status().isBadRequest());

        assertThat(nameOf(email)).isEqualTo(originalName);
    }

    @Test
    @DisplayName("[T1-06d] 칭호 반영 후 이름 길이 위반이 터지면 500이고 대표 칭호도 저장되지 않는다")
    void updateMyInfoWithOversizedNameAndOwnedTitleFailsWithoutPersistingTitle() throws Exception {
        String email = uniqueEmail("u6d");
        Member member = saveMember(email);
        Title title = saveTitle();
        grantTitle(member, title);
        authenticateAs(email);

        mockMvc.perform(put("/api/members/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "n".repeat(21) + "\",\"representative_title_id\":"
                                + title.getId() + "}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."));

        assertThat(representativeTitleIdOf(member)).isNull();
    }

    @Test
    @DisplayName("[T1-07a·T1-07b] 대표 칭호 장착과 해제는 모두 200과 동일 메시지를 반환한다")
    void updateRepresentativeTitleEquipsAndClearsWithSameMessage() throws Exception {
        String email = uniqueEmail("t7a");
        Member member = saveMember(email);
        Title title = saveTitle();
        grantTitle(member, title);
        UserDetails principal = userDetailsPrincipal(email);

        ResponseEntity<String> equipped =
                memberController.updateRepresentativeTitle(principal, titleSelectRequest(title.getId()));

        assertThat(equipped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(equipped.getBody()).isEqualTo("대표 칭호가 변경되었습니다.");
        assertThat(representativeTitleIdOf(member)).isEqualTo(title.getId());

        ResponseEntity<String> cleared =
                memberController.updateRepresentativeTitle(principal, titleSelectRequest(null));

        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody()).isEqualTo("대표 칭호가 변경되었습니다.");
        assertThat(representativeTitleIdOf(member)).isNull();
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
    @DisplayName("[T1-07d] 보유하지 않은 칭호로 대표 칭호를 변경하면 400과 별도 메시지를 반환한다")
    void updateRepresentativeTitleWithUnownedTitleReturnsBadRequest() throws Exception {
        String email = uniqueEmail("t7d");
        saveMember(email);
        Title unownedTitle = saveTitle();
        authenticateWithUserDetails(email);

        mockMvc.perform(patch("/api/members/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":" + unownedTitle.getId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("획득하지 않은 칭호는 장착할 수 없습니다."));
    }

    @Test
    @DisplayName("[T1-08] 로컬파트가 24자인 이메일로 가입하면 제약 위반이 변환 없이 전파되어 500이다")
    void signupWithOversizedLocalPartReturnsInternalServerError() throws Exception {
        String email = "t08" + uniqueSuffix().substring(0, 21) + "@test.com";

        mockMvc.perform(post("/api/members/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."));

        assertThat(memberRepository.findByEmail(email)).isEmpty();
    }

    @Test
    @DisplayName("[T1-09] 카카오 가입은 같은 제약 위반을 IllegalArgumentException으로 변환한다")
    void kakaoCompleteSignupWithOversizedNameThrowsIllegalArgument() {
        String email = uniqueEmail("t09");

        assertThatThrownBy(() -> kakaoAuthService.completeSignup(email, "n".repeat(25), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("회원 저장 실패: ");
    }

    @Test
    @DisplayName("[T2-01] 내 정보 조회는 본인 정보를, 이메일 존재 확인은 true/false를 반환한다")
    void getMyInfoAndExistsByEmailReturnCurrentState() {
        String email = uniqueEmail("t21");
        saveMember(email);
        authenticateAs(email);

        ResponseEntity<MemberResponse> response = memberController.getMyInfo();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getEmail()).isEqualTo(email);

        assertThat(memberController.existsByEmail(email).getBody()).containsEntry("exists", true);
        assertThat(memberController.existsByEmail(uniqueEmail("nil")).getBody()).containsEntry("exists", false);
    }

    @Test
    @DisplayName("[T2-02] DB에 없는 인증 주체의 계좌 목록 조회는 404와 빈 본문을 반환한다")
    void getMyAccountsForUnknownMemberReturnsNotFound() {
        authenticateAs(uniqueEmail("t22"));

        var response = memberController.getMyAccounts();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("[T2-03] FCM 토큰 등록과 삭제는 각각 200과 고정 메시지를 반환하고 DB에 반영된다")
    void registerAndRemoveFcmTokenReflectInDatabase() {
        String email = uniqueEmail("t23");
        saveMember(email);
        authenticateAs(email);

        ResponseEntity<String> registered = memberController.registerFcmToken(new FcmTokenRequest("fcm-token-1"));

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(registered.getBody()).isEqualTo("FCM 토큰이 등록되었습니다.");
        assertThat(memberRepository.findByEmail(email).orElseThrow().getFcmToken()).isEqualTo("fcm-token-1");

        ResponseEntity<String> removed = memberController.removeFcmToken();

        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody()).isEqualTo("FCM 토큰이 삭제되었습니다.");
        assertThat(memberRepository.findByEmail(email).orElseThrow().getFcmToken()).isNull();
    }

    @Test
    @DisplayName("[T2-04] 알림 설정 변경은 true/false 모두 200과 고정 메시지를 반환하고 DB에 반영된다")
    void updateNotificationSettingsReflectInDatabase() {
        String email = uniqueEmail("t24");
        saveMember(email);
        authenticateAs(email);

        ResponseEntity<String> disabled =
                memberController.updateNotificationSettings(new NotificationSettingsRequest(false));

        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabled.getBody()).isEqualTo("알림 설정이 변경되었습니다.");
        assertThat(memberRepository.findByEmail(email).orElseThrow().isExecutionNotificationEnabled()).isFalse();

        ResponseEntity<String> enabled =
                memberController.updateNotificationSettings(new NotificationSettingsRequest(true));

        assertThat(enabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(enabled.getBody()).isEqualTo("알림 설정이 변경되었습니다.");
        assertThat(memberRepository.findByEmail(email).orElseThrow().isExecutionNotificationEnabled()).isTrue();
    }

    @Test
    @DisplayName("[T2-05] 대표 칭호 조회는 미장착이면 필드가 모두 null인 객체를, 장착 후에는 칭호 정보를 반환한다")
    void getRepresentativeTitleReturnsNullFieldObjectThenTitle() throws Exception {
        String email = uniqueEmail("t25");
        Member member = saveMember(email);
        Title title = saveTitle();
        grantTitle(member, title);
        UserDetails principal = userDetailsPrincipal(email);

        ResponseEntity<RepresentativeTitleResponse> empty =
                memberController.getMyRepresentativeTitle(principal);

        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(empty.getBody()).isNotNull();
        assertThat(empty.getBody().getTitleId()).isNull();
        assertThat(empty.getBody().getName()).isNull();
        assertThat(empty.getBody().getDescription()).isNull();

        memberController.updateRepresentativeTitle(principal, titleSelectRequest(title.getId()));

        ResponseEntity<RepresentativeTitleResponse> equipped =
                memberController.getMyRepresentativeTitle(principal);

        assertThat(equipped.getBody()).isNotNull();
        assertThat(equipped.getBody().getTitleId()).isEqualTo(title.getId());
        assertThat(equipped.getBody().getName()).isEqualTo(title.getName());
        assertThat(equipped.getBody().getDescription()).isEqualTo(title.getDescription());
    }

    @Test
    @DisplayName("[T2-06] 설문조사는 false에서 완료 처리 후 true가 되며 응답 키는 survey_completed이다")
    void surveyCompletionFlowFlipsFlag() {
        String email = uniqueEmail("t26");
        saveMember(email);
        UserDetails principal = userDetailsPrincipal(email);

        assertThat(memberController.getSurveyCompleted(principal).getBody())
                .containsEntry("survey_completed", false);

        ResponseEntity<String> completed = memberController.completeSurvey(principal);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).isEqualTo("설문조사가 완료 처리되었습니다.");
        assertThat(memberController.getSurveyCompleted(principal).getBody())
                .containsEntry("survey_completed", true);
    }

    @Test
    @DisplayName("[T2-07] 인증된 로그아웃은 200과 고정 메시지를 반환한다")
    void logoutReturnsOkWithFixedMessage() {
        String email = uniqueEmail("t27");
        saveMember(email);
        authenticateAs(email);

        ResponseEntity<String> response = memberController.logout();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("로그아웃되었습니다.");
    }

    @Test
    @DisplayName("[U-01] 미인증 상태의 로그아웃·FCM·알림 설정은 401과 동일한 평문 메시지를 반환한다")
    void unauthenticatedPlainTextEndpointsReturnUnauthorized() {
        assertThat(memberController.logout().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(memberController.logout().getBody()).isEqualTo("인증되지 않은 요청입니다.");

        ResponseEntity<String> registered = memberController.registerFcmToken(new FcmTokenRequest("fcm-token"));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(registered.getBody()).isEqualTo("인증되지 않은 요청입니다.");

        ResponseEntity<String> removed = memberController.removeFcmToken();
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(removed.getBody()).isEqualTo("인증되지 않은 요청입니다.");

        ResponseEntity<String> settings =
                memberController.updateNotificationSettings(new NotificationSettingsRequest(true));
        assertThat(settings.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(settings.getBody()).isEqualTo("인증되지 않은 요청입니다.");
    }

    @Test
    @DisplayName("[U-02] 미인증 상태의 내 정보·수정·계좌 목록은 401과 빈 본문을 반환한다")
    void unauthenticatedEmptyBodyEndpointsReturnUnauthorized() {
        ResponseEntity<MemberResponse> myInfo = memberController.getMyInfo();
        assertThat(myInfo.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(myInfo.getBody()).isNull();

        ResponseEntity<MemberResponse> updated = memberController.updateMyInfo(
                new MemberUpdateRequest(null, null, null, null, null, null, null, null));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(updated.getBody()).isNull();

        var accounts = memberController.getMyAccounts();
        assertThat(accounts.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(accounts.getBody()).isNull();
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
