package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.entity.Reward;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

// 미션 트랜잭션은 보상 전에 계좌를 먼저 읽어 둔다(기본 계좌 확인, 파산 신청의 자산 계산).
// 그 사이 정산이 같은 계좌를 커밋하면, 읽어 둔 엔티티로 예수금을 올리는 보상이 정산을 덮어쓰면 안 된다.
@DisplayName("미션 보상 예수금 지급 — 정산과 겹쳐도 갱신이 사라지지 않는다 (통합 테스트)")
class MissionRewardCashConcurrencyTest extends IntegrationTestSupport {

    private static final BigDecimal INITIAL_CASH = new BigDecimal("10000000");
    private static final BigDecimal SETTLED_AMOUNT = new BigDecimal("300000");
    private static final long REWARD_AMOUNT = 1_000_000L;

    @Autowired private MissionRewardService missionRewardService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private AccountRepository accountRepository;

    @Test
    @DisplayName("보상 트랜잭션이 계좌를 읽은 뒤 정산이 커밋돼도 둘 다 반영된다")
    void rewardAfterConcurrentSettlement_keepsBothChanges() {
        Account account = createDefaultAccount();
        Long accountId = account.getAccountId();
        Long memberId = account.getMember().getMemberId();
        Reward reward = Reward.builder().description("테스트 보상").moneyAmount(REWARD_AMOUNT).build();

        transactionTemplate.executeWithoutResult(status -> {
            Member member = memberRepository.findById(memberId).orElseThrow();
            accountRepository.findById(accountId).orElseThrow();

            CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(settlement ->
                    accountRepository.findByIdWithLock(accountId).orElseThrow().decreaseCash(SETTLED_AMOUNT)))
                    .join();

            missionRewardService.distributeReward(member, reward);
        });

        BigDecimal cash = accountRepository.findById(accountId).orElseThrow().getCash();
        assertThat(cash).isEqualByComparingTo(
                INITIAL_CASH.subtract(SETTLED_AMOUNT).add(BigDecimal.valueOf(REWARD_AMOUNT)));
    }

    private Account createDefaultAccount() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("보상 " + suffix).email("reward-" + suffix + "@test.com").provider(AuthProvider.LOCAL).build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("보상 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        return accountRepository.save(Account.builder()
                .member(member).contest(contest)
                .accountName("보상 계좌 " + suffix)
                .cash(INITIAL_CASH)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(true)
                .build());
    }
}
