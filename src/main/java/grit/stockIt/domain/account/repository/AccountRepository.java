package grit.stockIt.domain.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.contest.entity.Contest;

import java.util.Optional;
import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByMemberAndContest(Member member, Contest contest);
    List<Account> findByMember(Member member);
    List<Account> findByContest(Contest contest);
}