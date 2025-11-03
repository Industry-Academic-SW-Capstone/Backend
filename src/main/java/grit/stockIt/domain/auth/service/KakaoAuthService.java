package grit.stockIt.domain.auth.service;

import grit.stockIt.domain.auth.client.KakaoOAuthClient;
import grit.stockIt.domain.auth.dto.KakaoLoginResponse;
import grit.stockIt.domain.auth.dto.KakaoSignupResponse;
import grit.stockIt.domain.auth.dto.KakaoTokenResponse;
import grit.stockIt.domain.auth.dto.KakaoUserInfoResponse;
import grit.stockIt.domain.auth.entity.KakaoToken;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.jwt.JwtService;
import grit.stockIt.global.jwt.JwtToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KakaoAuthService {

    private final KakaoOAuthClient kakaoOAuthClient;
    private final MemberRepository memberRepository;
    private final JwtService jwtService;

    /**
     * 카카오 로그인 (비동기 처리)
     */
    @Transactional
    public CompletableFuture<KakaoLoginResponse> login(String code) {
        return kakaoOAuthClient.getAccessToken(code)
                .flatMap(tokenResponse -> 
                    kakaoOAuthClient.getUserInfo(tokenResponse.getAccessToken())
                            .map(userInfo -> processLogin(tokenResponse, userInfo))
                )
                .toFuture();
    }

    private KakaoLoginResponse processLogin(KakaoTokenResponse tokenResponse, KakaoUserInfoResponse userInfo) {
        String email = userInfo.getKakaoAccount().getEmail();
        if (email == null) {
            throw new IllegalArgumentException("카카오 계정에 이메일 정보가 없습니다.");
        }

        Optional<Member> existingMember = memberRepository.findByEmail(email);

        if (existingMember.isPresent()) {
            Member member = existingMember.get();
            updateKakaoToken(member, tokenResponse);

            String jwt = jwtService.generateToken(member.getEmail());
            JwtToken jwtToken = JwtToken.builder().accessToken(jwt).build();
            
            return KakaoLoginResponse.ofExistingUser(jwtToken);
        } else {
            var profile = userInfo.getKakaoAccount().getProfile();
            String nickname = (profile != null) ? profile.getNickname() : null;
            String profileImage = (profile != null) ? profile.getProfileImageUrl() : null;

            KakaoSignupResponse signupInfo = KakaoSignupResponse.builder()
                    .email(email)
                    .name(nickname)
                    .profileImage(profileImage)
                    .build();

            return KakaoLoginResponse.ofNewUser(signupInfo);
        }
    }

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
                    .provider(AuthProvider.KAKAO)
                    .build();

            memberRepository.save(member);

            String jwt = jwtService.generateToken(member.getEmail());
            return JwtToken.builder().accessToken(jwt).build();

        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("회원 저장 실패: 필수 컬럼 누락 또는 제약 조건 위반(" +
                    e.getMostSpecificCause().getMessage() + ")");
        }
    }

    @Transactional
    @Async
    public CompletableFuture<Void> logout(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        if (member.getKakaoToken() != null) {
            String kakaoAccessToken = member.getKakaoToken().getAccessToken();
            return kakaoOAuthClient.logout(kakaoAccessToken)
                    .doOnSuccess(v -> log.info("카카오 로그아웃 완료: {}", email))
                    .doOnError(e -> log.warn("카카오 로그아웃 실패(무시): {}", e.getMessage()))
                    .then()
                    .toFuture();
        }
        
        log.info("로그아웃 완료: {}", email);
        return CompletableFuture.completedFuture(null);
    }

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
            
            member.updateKakaoToken(kakaoToken);
        } else {
            member.getKakaoToken().updateAllTokens(
                    tokenResponse.getAccessToken(),
                    accessTokenExpiry,
                    tokenResponse.getRefreshToken(),
                    refreshTokenExpiry
            );
        }
    }
}