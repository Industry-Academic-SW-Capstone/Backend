package grit.stockIt.domain.member.service;

import grit.stockIt.domain.account.service.AccountService;
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
public class LocalMemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountService accountService;

    /**
     * 로컬 회원가입 (이메일 기준)
     */
    @Transactional
    public MemberResponse signup(MemberSignupRequest request) {
        // 이메일 중복 검증
        validateDuplicateEmail(request.getEmail());

        // 비밀번호 암호화
        String encodedPassword = passwordEncoder.encode(request.getPassword());

        // 회원 생성 (로컬 회원)
        Member member = Member.builder()
                .name(request.getName())
                .email(request.getEmail())
                .password(encodedPassword)
                .profileImage(request.getProfileImage())
                .provider(AuthProvider.LOCAL) // 로컬 사용자로 설정
                .build();

        Member savedMember = memberRepository.save(member);

        // 디폴트 계좌 생성 (회원당 1개 보장)
        accountService.createDefaultAccountForMember(savedMember);

        return MemberResponse.from(savedMember);
    }

    /**
     * 로그인 (이메일 + 비밀번호)
     */
    public JwtToken login(MemberLoginRequest request) {
        // 회원 조회 (이메일)
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 이메일입니다."));

        // 비밀번호 검증
        if (!passwordEncoder.matches(request.getPassword(), member.getPassword())) {
            throw new IllegalArgumentException("비밀번호가 일치하지 않습니다.");
        }

        // JWT 토큰 생성 (식별자로 이메일 사용)
        String accessToken = jwtService.generateToken(member.getEmail());
        return JwtToken.builder()
                .accessToken(accessToken)
                .build();
    }

    /**
     * 로그아웃
     *
     * 현재 로컬 인증 저장소에 리프레시 토큰을 보관하지 않으면 서버 측에서 할 작업이 제한됩니다.
     * 필요 시 리프레시 토큰을 DB/Redis에 저장한 후 여기서 삭제하거나, JWT 블랙리스트 처리를 구현하세요.
     */
    @Transactional
    public void logout(String email) {
        // TODO: 리프레시 토큰/세션을 저장하는 전략 도입 시 토큰 삭제 로직 추가
        // 예) memberRepository.findByEmail(email).ifPresent(m -> m.clearRefreshToken());
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