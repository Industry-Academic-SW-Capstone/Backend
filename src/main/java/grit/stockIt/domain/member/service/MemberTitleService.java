package grit.stockIt.domain.member.service;

import grit.stockIt.contract.ExtraQueryClient;
import grit.stockIt.contract.TitleView;
import grit.stockIt.domain.member.dto.RepresentativeTitleResponse;
import grit.stockIt.domain.member.dto.TitleResponse;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 대표 칭호 조회·변경.
 *
 * 장착한 칭호는 회원 프로필 설정이라 증권사가 쓴다. 칭호 자체와 보유 여부는 부가 데이터라 ExtraQueryClient 로 묻는다.
 *
 * 클래스 레벨 트랜잭션 선언을 두지 않는다. equipRepresentativeTitle이 호출자의
 * 트랜잭션·저장 시점에 그대로 참여해야 하므로, 트랜잭션 경계는 메서드마다 개별 선언한다.
 */
@Service
@RequiredArgsConstructor
public class MemberTitleService {

    private final MemberRepository memberRepository;
    private final ExtraQueryClient extraQueryClient;

    @Transactional(readOnly = true)
    public RepresentativeTitleResponse getRepresentativeTitle(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        TitleView title = member.getRepresentativeTitleId() == null ? null
                : extraQueryClient.title(member.getRepresentativeTitleId()).orElse(null);
        return RepresentativeTitleResponse.from(title);
    }

    @Transactional
    public void updateRepresentativeTitle(String email, Long titleId) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        if (titleId == null) {
            member.updateRepresentativeTitle(null, null);
            // 더티 체킹으로 반영되므로 저장 호출 없이 종료
            return;
        }

        TitleView title = findTitle(titleId);

        if (!owns(member, title)) {
            throw new IllegalArgumentException("획득하지 않은 칭호는 장착할 수 없습니다.");
        }

        member.updateRepresentativeTitle(title.titleId(), title.name());

        memberRepository.save(member);
    }

    /**
     * 이미 조회된 회원에 칭호를 장착한다.
     *
     * 트랜잭션을 열지 않고 저장도 하지 않는다 — 호출자의 트랜잭션과 단일 save 지점을 그대로 사용한다.
     */
    public void equipRepresentativeTitle(Member member, Long titleId) {
        TitleView title = findTitle(titleId);

        if (!owns(member, title)) {
            throw new IllegalArgumentException("보유하지 않은 칭호는 장착할 수 없습니다.");
        }

        member.updateRepresentativeTitle(title.titleId(), title.name());
    }

    private TitleView findTitle(Long titleId) {
        return extraQueryClient.title(titleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 칭호입니다."));
    }

    // 회원 프로필에 보여 줄 보유 칭호 목록. 칭호는 부가 서비스의 데이터다.
    public List<TitleResponse> titlesOf(Long memberId) {
        return extraQueryClient.titlesOf(memberId).stream()
                .map(TitleResponse::from)
                .toList();
    }

    private boolean owns(Member member, TitleView title) {
        return extraQueryClient.ownsTitle(member.getMemberId(), title.titleId());
    }
}
