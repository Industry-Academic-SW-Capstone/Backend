package grit.stockIt.domain.member.service;

import grit.stockIt.domain.account.service.AccountService;
import grit.stockIt.domain.member.dto.*;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.service.MissionService;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import grit.stockIt.domain.title.repository.TitleRepository;
import grit.stockIt.global.jwt.JwtService;
import grit.stockIt.global.jwt.JwtToken;
import lombok.RequiredArgsConstructor;
import java.util.Optional;
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
    private final MissionService missionService; // 미션 보상 지급용
    private final TitleRepository titleRepository;
    private final MemberTitleRepository memberTitleRepository;

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

        Member savedMember = memberRepository.save(member);

        // 디폴트 계좌 생성 (회원당 1개 보장)
        accountService.createDefaultAccountForMember(savedMember);

        //  미션 시스템 초기화
        missionService.initializeMissionsForNewMember(savedMember);

        return MemberResponse.from(savedMember);
    }

    @Transactional(readOnly = true)
    public MemberResponse getMemberByEmail(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        return MemberResponse.from(member);
    }

    @Transactional
    public MemberResponse updateMember(String email, MemberUpdateRequest request) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        // update optional profile fields
        if (request.getName() != null || request.getProfileImage() != null) {
            member.updateProfile(request.getName(), request.getProfileImage());
        }

        if (request.getTwoFactorEnabled() != null) {
            member.setTwoFactorEnabled(request.getTwoFactorEnabled());
        }

        if (request.getNotificationAgreement() != null) {
            member.setNotificationAgreement(request.getNotificationAgreement());
        }

        if (request.getMainTutorialCompleted() != null) {
            member.setMainTutorialCompleted(request.getMainTutorialCompleted());
        }

        if (request.getSecuritiesDepthTutorialCompleted() != null) {
            member.setSecuritiesDepthTutorialCompleted(request.getSecuritiesDepthTutorialCompleted());
        }

        if (request.getStockDetailTutorialCompleted() != null) {
            member.setStockDetailTutorialCompleted(request.getStockDetailTutorialCompleted());
        }

        // 대표 칭호 장착
        if (request.getRepresentativeTitleId() != null) {
            Title title = titleRepository.findById(request.getRepresentativeTitleId())
                    .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 칭호입니다."));
            
            // 해당 유저가 이 칭호를 보유하고 있는지 확인
            boolean hasTitle = memberTitleRepository.existsByMemberAndTitle(member, title);
            if (!hasTitle) {
                throw new IllegalArgumentException("보유하지 않은 칭호는 장착할 수 없습니다.");
            }
            
            member.updateRepresentativeTitle(title);
        }

        Member saved = memberRepository.save(member);
        return MemberResponse.from(saved);
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

    // FCM 토큰 등록/업데이트
    @Transactional
    public void updateFcmToken(String email, String fcmToken) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        
        member.updateFcmToken(fcmToken);
        memberRepository.save(member);
    }

    // FCM 토큰 삭제
    @Transactional
    public void removeFcmToken(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        
        member.removeFcmToken();
        memberRepository.save(member);
    }

    /**
     * 이메일이 이미 존재하는지 확인 (회원가입 전 중복 체크 등)
     * @param email 확인할 이메일
     * @return 존재하면 true
     */
    @Transactional(readOnly = true)
    public boolean existsByEmail(String email) {
        return memberRepository.existsByEmail(email);
    }

    /**
     * 회원 엔티티를 Optional로 반환합니다. 컨트롤러에서 엔티티가 필요한 경우 사용하세요.
     * (계좌 조회 등 엔티티 전달이 필요한 상황에서 Repository 접근을 컨트롤러에 두지 않기 위해 추가)
     */
    @Transactional(readOnly = true)
    public Optional<Member> findMemberEntityByEmail(String email) {
        return memberRepository.findByEmail(email);
    }

    // 체결 알림 설정 변경
    @Transactional
    public void updateExecutionNotificationSettings(String email, boolean enabled) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        
        if (enabled) {
            member.enableExecutionNotification();
        } else {
            member.disableExecutionNotification();
        }
        memberRepository.save(member);
    }

    @Transactional
    public void updateRepresentativeTitle(String email, Long titleId) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        // 1. 해제 요청
        if (titleId == null) {
            member.updateRepresentativeTitle(null);
            // memberRepository.save(member); // 확실하게 하려면 추가
            return;
        }

        // 2. 칭호 조회
        Title title = titleRepository.findById(titleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 칭호입니다."));

        // 3. 보유 여부 검증
        boolean hasTitle = memberTitleRepository.existsByMemberAndTitle(member, title);
        if (!hasTitle) {
            throw new IllegalArgumentException("획득하지 않은 칭호는 장착할 수 없습니다.");
        }

        // 4. 업데이트 수행
        member.updateRepresentativeTitle(title);

        // [추가 권장] 명시적 저장으로 변경사항 즉시 반영 보장
        memberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public RepresentativeTitleResponse getRepresentativeTitle(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        return RepresentativeTitleResponse.from(member.getRepresentativeTitle());
    }
}