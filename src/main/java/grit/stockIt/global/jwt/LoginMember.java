package grit.stockIt.global.jwt;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 토큰에서 세운 로그인 회원. 회원 테이블을 읽지 않고 토큰의 회원 번호·이메일만으로 만든다 — 회원 테이블이 없는 서비스(부가)도
// 같은 토큰으로 사용자를 안다. getUsername 은 이메일이라 이메일로 찾던 증권사 코드는 그대로 동작한다.
public record LoginMember(Long memberId, String email, List<GrantedAuthority> authorities) implements UserDetails {

    public static Optional<LoginMember> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginMember loginMember) {
            return Optional.of(loginMember);
        }
        return Optional.empty();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return email;
    }
}
