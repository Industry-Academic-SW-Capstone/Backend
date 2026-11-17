package grit.stockIt.domain.member.queue;

import grit.stockIt.contract.ExtraEvent;
import grit.stockIt.contract.InvalidExtraEventException;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("증권사 부가 이벤트 워커 — 보상 지급·티어 복사 (통합 테스트)")
class ExtraEventConsumerIntegrationTest extends IntegrationTestSupport {

    private static final BigDecimal INITIAL_CASH = new BigDecimal("10000000");
    private static final BigDecimal SETTLED_AMOUNT = new BigDecimal("300000");
    private static final long REWARD_AMOUNT = 1_000_000L;

    @Autowired private ExtraEventConsumer extraEventConsumer;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private AccountRepository accountRepository;

    @Test
    @DisplayName("보상 지급 요청은 기본 계좌 예수금에 더해진다")
    void rewardGranted_increasesDefaultAccountCash() {
        Account account = createDefaultAccount();

        extraEventConsumer.consume(rewardEvent(memberIdOf(account)));

        assertThat(cashOf(account)).isEqualByComparingTo(INITIAL_CASH.add(BigDecimal.valueOf(REWARD_AMOUNT)));
    }

    @Test
    @DisplayName("같은 지급 요청이 다시 와도(최소 한 번 전달) 한 번만 지급한다")
    void sameRewardEventTwice_paysOnce() {
        Account account = createDefaultAccount();
        ExtraEvent event = rewardEvent(memberIdOf(account));

        extraEventConsumer.consume(event);
        extraEventConsumer.consume(event);

        assertThat(cashOf(account)).isEqualByComparingTo(INITIAL_CASH.add(BigDecimal.valueOf(REWARD_AMOUNT)));
    }

    // 정산이 계좌를 잠근 동안 온 지급은 기다렸다가 정산이 커밋한 잔고 위에 더한다.
    @Test
    @DisplayName("정산이 계좌를 잠근 사이 온 보상 지급도 정산을 덮어쓰지 않는다")
    void rewardDuringSettlementLock_keepsBothChanges() throws Exception {
        Account account = createDefaultAccount();
        Long accountId = account.getAccountId();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> settlement = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    accountRepository.findByIdWithLock(accountId).orElseThrow().decreaseCash(SETTLED_AMOUNT);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> reward = CompletableFuture.runAsync(() ->
                extraEventConsumer.consume(rewardEvent(memberIdOf(account))));
        // 지급이 계좌 잠금에서 기다리는지 확인한다.
        assertThatThrownBy(() -> reward.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        settlement.get(10, TimeUnit.SECONDS);
        reward.get(10, TimeUnit.SECONDS);

        assertThat(cashOf(account)).isEqualByComparingTo(
                INITIAL_CASH.subtract(SETTLED_AMOUNT).add(BigDecimal.valueOf(REWARD_AMOUNT)));
    }

    @Test
    @DisplayName("티어 변화는 회원 테이블의 티어 복사본을 바꾼다")
    void tierChanged_updatesMemberTier() {
        Account account = createDefaultAccount();
        Long memberId = memberIdOf(account);
        assertThat(memberRepository.findById(memberId).orElseThrow().getTier()).isEqualTo("BRONZE 1");

        extraEventConsumer.consume(ExtraEvent.tierChanged(memberId, "GOLD 2"));

        assertThat(memberRepository.findById(memberId).orElseThrow().getTier()).isEqualTo("GOLD 2");
    }

    @Test
    @DisplayName("내용이 빠진 이벤트는 다시 시도하지 않을 예외로 거부한다")
    void invalidEvent_isRejected() {
        ExtraEvent invalid = ExtraEvent.rewardGranted(1L, new ExtraEvent.RewardGranted(1L, 0));

        assertThatThrownBy(() -> extraEventConsumer.consume(invalid))
                .isInstanceOf(InvalidExtraEventException.class);
    }

    private static ExtraEvent rewardEvent(Long memberId) {
        return ExtraEvent.rewardGranted(memberId, new ExtraEvent.RewardGranted(1L, REWARD_AMOUNT));
    }

    private Long memberIdOf(Account account) {
        return account.getMember().getMemberId();
    }

    private BigDecimal cashOf(Account account) {
        return accountRepository.findById(account.getAccountId()).orElseThrow().getCash();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
