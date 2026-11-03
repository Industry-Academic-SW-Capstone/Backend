package grit.stockIt.domain.title.service;

import grit.stockIt.contract.ExtraQueryClient;
import grit.stockIt.contract.TitleView;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import grit.stockIt.domain.title.repository.TitleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// 부가가 증권사의 칭호 조회에 답한다. 지금은 같은 프로세스의 메서드 호출이고, 서비스를 나누면 이 내용이 HTTP 엔드포인트가 된다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalExtraQueryClient implements ExtraQueryClient {

    private final TitleRepository titleRepository;
    private final MemberTitleRepository memberTitleRepository;

    @Override
    public Optional<TitleView> title(Long titleId) {
        return titleRepository.findById(titleId).map(LocalExtraQueryClient::toView);
    }

    @Override
    public boolean ownsTitle(Long memberId, Long titleId) {
        return memberTitleRepository.existsByMemberIdAndTitleId(memberId, titleId);
    }

    @Override
    public List<TitleView> titlesOf(Long memberId) {
        return memberTitleRepository.findAllByMemberId(memberId).stream()
                .map(memberTitle -> toView(memberTitle.getTitle()))
                .toList();
    }

    private static TitleView toView(Title title) {
        return new TitleView(title.getId(), title.getName(), title.getDescription());
    }
}
