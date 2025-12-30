package grit.stockIt.domain.account.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.ForbiddenException;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final ContestRepository contestRepository;
    private final MemberRepository memberRepository;

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

    /**
     * 특정 대회에 대해 회원용 계좌를 생성합니다.
     * - isDefault = false
     * - 초기 cash = contest.seedMoney
     */
    @Transactional
    public Account createAccountForContest(Member member, Long contestId, String accountName, String providedPassword) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 대회입니다."));

        // 비공개 대회인 경우 비밀번호 검증
        if (contest.getPassword() != null && !contest.getPassword().isBlank()) {
            if (providedPassword == null || providedPassword.isBlank()) {
                throw new BadRequestException("비밀번호가 필요한 대회입니다.");
            }
            if (!contest.getPassword().equals(providedPassword)) {
                throw new ForbiddenException("대회 비밀번호가 일치하지 않습니다.");
            }
        }

        Optional<Account> existing = accountRepository.findByMemberAndContest(member, contest);
        if (existing.isPresent()) {
            throw new IllegalArgumentException("이미 해당 대회에 가입된 계좌가 존재합니다.");
        }

        Account account = Account.builder()
                .member(member)
                .contest(contest)
                .accountName(accountName)
                .cash(BigDecimal.valueOf(contest.getSeedMoney() != null ? contest.getSeedMoney() : 0L))
                .isDefault(false)
                .build();

        try {
            return accountRepository.save(account);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Account create conflict member={} contest={}, reloading", member.getMemberId(), contest.getContestId());
            return accountRepository.findByMemberAndContest(member, contest).orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public java.util.List<Account> getAccountsForMember(Member member) {
        return accountRepository.findByMember(member);
    }
}