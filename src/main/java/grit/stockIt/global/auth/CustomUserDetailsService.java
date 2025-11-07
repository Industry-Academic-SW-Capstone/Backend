package grit.stockIt.global.auth;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final MemberRepository memberRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.info("UserDetailsService: 이메일로 회원 조회 - {}", email);
        
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.error("회원을 찾을 수 없음: {}", email);
                    return new UsernameNotFoundException("회원을 찾을 수 없습니다: " + email);
                });
        
        log.info("UserDetailsService: 회원 찾음 - ID: {}, 이메일: {}", member.getMemberId(), member.getEmail());
        
        // Spring Security User 객체로 변환
        return User.builder()
                .username(member.getEmail())
                .password(member.getPassword() != null ? member.getPassword() : "")
                .authorities(new ArrayList<>()) // 권한은 필요시 추가
                .build();
    }
}
