package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 부가 서비스가 알린 티어를 회원 테이블에 복사해 둔다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberTierService {

    private final MemberRepository memberRepository;

    @Transactional
    public void updateTier(Long memberId, String tier) {
        memberRepository.findById(memberId)
                .ifPresentOrElse(
                        member -> member.updateTier(tier),
                        () -> log.warn("티어 갱신 대상 회원이 없습니다. memberId={}", memberId)
                );
    }
}
