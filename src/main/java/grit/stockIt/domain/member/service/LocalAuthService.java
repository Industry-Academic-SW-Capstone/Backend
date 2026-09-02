package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.dto.MemberLoginRequest;
import grit.stockIt.domain.member.dto.MemberResponse;
import grit.stockIt.domain.member.dto.MemberSignupRequest;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.jwt.JwtService;
import grit.stockIt.global.jwt.JwtToken;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalAuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MemberRegistrationService memberRegistrationService;

    /**
     * 로컬 회원가입 (이메일 기준)
     */
    @Transactional
    public MemberResponse signup(MemberSignupRequest request) {
        validateDuplicateEmail(request.getEmail());

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        String defaultName = request.getEmail() != null && request.getEmail().contains("@")
            ? request.getEmail().split("@")[0]
            : request.getEmail();

        Member member = Member.builder()
            .name(defaultName)
            .email(request.getEmail())
            .password(encodedPassword)
            .profileImage(null)
            .provider(AuthProvider.LOCAL) // 로컬 사용자로 설정
            .build();

        Member savedMember = memberRegistrationService.register(member);

        return MemberResponse.from(savedMember);
    }

    /**
     * 로그인 (이메일 + 비밀번호)
     */
    public JwtToken login(MemberLoginRequest request) {
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다."));

        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        String accessToken = jwtService.generateToken(member.getEmail());
        return JwtToken.builder()
                .accessToken(accessToken)
                .build();
    }

    /**
     * 이메일 중복 검증
     */
    private void validateDuplicateEmail(String email) {
        if (memberRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }
    }
}
