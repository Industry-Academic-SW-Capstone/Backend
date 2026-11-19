package grit.stockIt.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.cache.LocalTtlCache;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.jwt.JwtToken;
import grit.stockIt.global.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.jdbc.Sql;

@Sql(scripts = "/data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class KakaoSignupTicketIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private KakaoAuthService kakaoAuthService;

    @Autowired
    private LocalTtlCache localTtlCache;

    @Autowired
    private MemberRepository memberRepository;

    private static String uniqueEmail() {
        return "tkt" + UUID.randomUUID().toString().substring(0, 13).replace("-", "") + "@test.com";
    }

    private static String issueTicket(LocalTtlCache cache, String email) {
        String ticket = UUID.randomUUID().toString();
        cache.put("kakao:signup:" + ticket, email, Duration.ofMinutes(10));
        return ticket;
    }

    @Test
    @DisplayName("가입 표가 가리키는 카카오 이메일로 가입하고, 같은 표는 다시 쓸 수 없다")
    void signsUpWithTicketEmailOnlyOnce() {
        String email = uniqueEmail();
        String ticket = issueTicket(localTtlCache, email);

        JwtToken token = kakaoAuthService.completeSignupWithTicket(ticket, "카카오테스터", null);

        assertThat(token.getAccessToken()).isNotBlank();
        assertThat(memberRepository.findByEmail(email))
                .hasValueSatisfying(member -> assertThat(member.getProvider()).isEqualTo(AuthProvider.KAKAO));
        assertThatThrownBy(() -> kakaoAuthService.completeSignupWithTicket(ticket, "카카오테스터", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("발급하지 않은 표로는 가입할 수 없다")
    void rejectsUnknownTicket() {
        assertThatThrownBy(() -> kakaoAuthService.completeSignupWithTicket(UUID.randomUUID().toString(), "카카오테스터", null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("가입이 실패하면 표를 남겨 이름만 고쳐 다시 시도할 수 있다")
    void keepsTicketWhenSignupFails() {
        String email = uniqueEmail();
        String ticket = issueTicket(localTtlCache, email);

        assertThatThrownBy(() -> kakaoAuthService.completeSignupWithTicket(ticket, "n".repeat(25), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(kakaoAuthService.completeSignupWithTicket(ticket, "카카오테스터", null).getAccessToken()).isNotBlank();
    }
}
