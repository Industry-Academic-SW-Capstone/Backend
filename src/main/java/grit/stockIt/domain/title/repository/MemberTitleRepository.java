package grit.stockIt.domain.title.repository;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.entity.Title;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemberTitleRepository extends JpaRepository<MemberTitle, Long> {

    // 회원이 특정 칭호를 이미 가지고 있는지 확인
    boolean existsByMemberAndTitle(Member member, Title title);

    // 회원이 보유한 모든 칭호 조회
    List<MemberTitle> findAllByMember(Member member);
}