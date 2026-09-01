package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.dto.RepresentativeTitleResponse;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import grit.stockIt.domain.title.repository.TitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대표 칭호 조회·변경.
 *
 * 클래스 레벨 트랜잭션 선언을 두지 않는다. equipRepresentativeTitle이 호출자의
 * 트랜잭션·저장 시점에 그대로 참여해야 하므로, 트랜잭션 경계는 메서드마다 개별 선언한다.
 */
@Service
@RequiredArgsConstructor
public class MemberTitleService {

    private final MemberRepository memberRepository;
    private final TitleRepository titleRepository;
    private final MemberTitleRepository memberTitleRepository;

    @Transactional(readOnly = true)
    public RepresentativeTitleResponse getRepresentativeTitle(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        return RepresentativeTitleResponse.from(member.getRepresentativeTitle());
    }

    @Transactional
    public void updateRepresentativeTitle(String email, Long titleId) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        // 1. 해제 요청
        if (titleId == null) {
            member.updateRepresentativeTitle(null);
            // 더티 체킹으로 반영되므로 저장 호출 없이 종료
            return;
        }

        // 2. 칭호 조회
        Title title = findTitle(titleId);

        // 3. 보유 여부 검증
        if (!owns(member, title)) {
            throw new IllegalArgumentException("획득하지 않은 칭호는 장착할 수 없습니다.");
        }

        // 4. 업데이트 수행
        member.updateRepresentativeTitle(title);

        memberRepository.save(member);
    }

    /**
     * 이미 조회된 회원에 칭호를 장착한다.
     *
     * 트랜잭션을 열지 않고 저장도 하지 않는다 — 호출자의 트랜잭션과 단일 save 지점을 그대로 사용한다.
     */
    public void equipRepresentativeTitle(Member member, Long titleId) {
        Title title = findTitle(titleId);

        if (!owns(member, title)) {
            throw new IllegalArgumentException("보유하지 않은 칭호는 장착할 수 없습니다.");
        }

        member.updateRepresentativeTitle(title);
    }

    private Title findTitle(Long titleId) {
        return titleRepository.findById(titleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 칭호입니다."));
    }

    private boolean owns(Member member, Title title) {
        return memberTitleRepository.existsByMemberAndTitle(member, title);
    }
}
