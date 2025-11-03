package grit.stockIt.domain.auth.service;

import grit.stockIt.domain.auth.client.KakaoOAuthClient;
import grit.stockIt.domain.auth.dto.KakaoSignupResponse;
import grit.stockIt.domain.auth.dto.KakaoTokenResponse;
import grit.stockIt.domain.auth.dto.KakaoUserInfoResponse;
import grit.stockIt.domain.auth.entity.KakaoToken;
import grit.stockIt.domain.member.entity.AuthProvider;   // 추가
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.jwt.JwtService;
import grit.stockIt.global.jwt.JwtToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KakaoAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final MemberRepository memberRepository;
    private final JwtService jwtService;

    /**
     * 카카오 로그인 (신규 회원이면 가입 정보 반환)
     */
    @Transactional
    public Object login(String code) {
        KakaoTokenResponse tokenResponse = kakaoOAuthClient.getAccessToken(code);
        KakaoUserInfoResponse userInfo = kakaoOAuthClient.getUserInfo(tokenResponse.getAccessToken());

        String email = userInfo.getKakaoAccount().getEmail();
        if (email == null) {
            throw new IllegalArgumentException("카카오 계정에 이메일 정보가 없습니다.");
        }

        Optional<Member> existingMember = memberRepository.findByEmail(email);

        if (existingMember.isPresent()) {
            Member member = existingMember.get();
            updateKakaoToken(member, tokenResponse);

            String jwt = jwtService.generateToken(member.getEmail());
            return JwtToken.builder().accessToken(jwt).build();
        } else {
            // 프로필 null 안전 처리
            var profile = userInfo.getKakaoAccount().getProfile();
            String nickname = (profile != null) ? profile.getNickname() : null;
            String profileImage = (profile != null) ? profile.getProfileImageUrl() : null;

            return KakaoSignupResponse.builder()
                    .email(email)
                    .name(nickname)
                    .profileImage(profileImage)
                    // .isNewUser(true)
                    .build();
        }
    }

    /**
     * 카카오 회원가입 완료 처리
     */
    @Transactional
    public JwtToken completeSignup(String email, String name, String profileImage) {
        if (memberRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }

        try {
            Member member = Member.builder()
                    .email(email)
                    .name(name)
                    .profileImage(profileImage)
                    .provider(AuthProvider.KAKAO)   // 핵심: provider 자동 세팅
                    .build();

            memberRepository.save(member);

            String jwt = jwtService.generateToken(member.getEmail());
            return JwtToken.builder().accessToken(jwt).build();

        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("회원 저장 실패: 필수 컬럼 누락 또는 제약 조건 위반(" +
                    e.getMostSpecificCause().getMessage() + ")");
        }
    }

    /**
     * 카카오 로그아웃
     */
    @Transactional
    public void logout(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        if (member.getKakaoToken() != null) {
            String kakaoAccessToken = member.getKakaoToken().getAccessToken();
            kakaoOAuthClient.logout(kakaoAccessToken);
        }
        
        log.info("카카오 로그아웃 완료: {}", email);
    }

    /**
     * 카카오 토큰 업데이트
     */
    private void updateKakaoToken(Member member, KakaoTokenResponse tokenResponse) {
        LocalDateTime accessTokenExpiry = LocalDateTime.now().plusSeconds(tokenResponse.getExpiresIn());
        LocalDateTime refreshTokenExpiry = tokenResponse.getRefreshTokenExpiresIn() != null
                ? LocalDateTime.now().plusSeconds(tokenResponse.getRefreshTokenExpiresIn())
                : null;

        if (member.getKakaoToken() == null) {
            KakaoToken kakaoToken = KakaoToken.builder()
                    .accessToken(tokenResponse.getAccessToken())
                    .accessTokenExpiresIn(accessTokenExpiry)
                    .refreshToken(tokenResponse.getRefreshToken())
                    .refreshTokenExpiresIn(refreshTokenExpiry)
                    .build();
            member.updateKakaoToken(kakaoToken);   // 주인 쪽에 세팅(주석 해제)
        } else {
            member.getKakaoToken().updateToken(tokenResponse.getAccessToken(), accessTokenExpiry);
        }
    }
}