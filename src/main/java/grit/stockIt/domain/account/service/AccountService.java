package grit.stockIt.domain.account.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.Member;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final ContestRepository contestRepository;

    /**
     * 회원이 사용할 디폴트 계좌를 생성하거나 이미 있으면 반환한다.
     * 트랜잭션 내에서 DB 유니크 제약 위반 발생 시 재조회로 안정화 처리.
     */
    @Transactional
    public Account createDefaultAccountForMember(Member member) {
        Contest defaultContest = contestRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("Default contest not found"));

        Optional<Account> existing = accountRepository.findByMemberAndContest(member, defaultContest);
        if (existing.isPresent()) {
            return existing.get();
        }

        Account account = Account.builder()
                .member(member)
                .contest(defaultContest)
                .accountName(member.getName() + " 기본계좌")
                .cash(BigDecimal.valueOf(defaultContest.getSeedMoney() != null ? defaultContest.getSeedMoney() : 0L))
                .isDefault(true)
                .build();

        try {
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Account create conflict for member={} contest={}, trying to reload", member.getMemberId(), defaultContest.getContestId());
            return accountRepository.findByMemberAndContest(member, defaultContest)
                    .orElseThrow(() -> ex);
        }
    }
}