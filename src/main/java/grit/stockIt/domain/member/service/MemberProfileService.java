package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.dto.MemberResponse;
import grit.stockIt.domain.member.dto.MemberUpdateRequest;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberProfileService {

    private final MemberRepository memberRepository;
    private final MemberTitleService memberTitleService;

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

        // 대표 칭호 장착 — 위임 대상은 저장하지 않는다. 이 메서드의 save 한 번이 유일한 저장 지점이다.
        if (request.getRepresentativeTitleId() != null) {
            memberTitleService.equipRepresentativeTitle(member, request.getRepresentativeTitleId());
        }

        Member saved = memberRepository.save(member);
        return MemberResponse.from(saved);
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

    // 설문조사 완료 여부 조회
    @Transactional(readOnly = true)
    public boolean getSurveyCompleted(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        return member.isSurveyCompleted();
    }

    // 설문조사 완료 처리
    @Transactional
    public void completeSurvey(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));
        member.setSurveyCompleted(true);
        memberRepository.save(member);
    }
}
