package grit.stockIt.global.auth;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final MemberRepository memberRepository;
    private final AdminEmailAllowlist adminEmailAllowlist;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("회원을 찾을 수 없습니다: " + email));

        return User.builder()
                .username(member.getEmail())
                .password(member.getPassword() != null ? member.getPassword() : "")
                .authorities(authoritiesOf(member.getEmail()))
                .build();
    }

    /**
     * 허용목록의 이메일에만 ROLE_ADMIN을 부여한다. 관리자 경로 판정은
     * {@code SecurityConfig}의 {@code hasRole("ADMIN")}이 담당한다.
     */
    private List<GrantedAuthority> authoritiesOf(String email) {
        return adminEmailAllowlist.isAdmin(email)
                ? List.of(new SimpleGrantedAuthority(ADMIN_ROLE))
                : List.of();
    }
}
