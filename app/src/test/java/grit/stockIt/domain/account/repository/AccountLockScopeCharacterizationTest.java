package grit.stockIt.domain.account.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// findByIdWithLock 이 실제로 어느 행을 잠그는지 고정한다.
//
// PostgreSQL 은 잠글 테이블을 명시하지 않은 행 잠금을 FROM 절의 모든 테이블에 적용한다. 그래서
// 이 쿼리가 member·contest 를 JOIN FETCH 하던 동안에는 조인된 행까지 잠겼고, 대회 행은 참가자
// 전원이 공유하므로 체결·정산이 종목·계좌와 무관하게 그 한 행에서 직렬화됐다. 연관을 조인에서
// 빼서 잠금이 계좌 행에서 멈추게 했고, 이 테스트가 그 범위를 고정한다.
//
// 스레드 경합 대신 NOWAIT 탐침을 쓴다. 잠겨 있으면 기다리지 않고 즉시 예외가 나므로 결정적이다.
@DisplayName("계좌 비관적 락의 잠금 범위 특성화 (통합 테스트)")
class AccountLockScopeCharacterizationTest extends IntegrationTestSupport {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private Contest sharedContest;
    private Member memberA;
    private Account accountA;
    private Account accountB;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);

        sharedContest = contestRepository.save(Contest.builder()
                .contestName("공유 대회 " + uniqueId)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(BigDecimal.ZERO)
                .isDefault(false)
                .build());

        memberA = saveMember("A", "lock-scope-a-" + uniqueId);
        Member memberB = saveMember("B", "lock-scope-b-" + uniqueId);

        accountA = saveAccount(memberA, "A " + uniqueId);
        accountB = saveAccount(memberB, "B " + uniqueId);
    }

    private Member saveMember(String name, String emailKey) {
        return memberRepository.save(Member.builder()
                .name(name)
                .email(emailKey + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());
    }

    private Account saveAccount(Member member, String name) {
        return accountRepository.save(Account.builder()
                .member(member)
                .contest(sharedContest)
                .accountName(name)
                .cash(new BigDecimal("100000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());
    }

    @Test
    @DisplayName("계좌 락이 대상 계좌 행에서 멈추는지 확인한다")
    void findByIdWithLock_locksOnlyTargetAccountRow() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        boolean[] locked = new boolean[4];

        template.execute(status -> {
            accountRepository.findByIdWithLock(accountA.getAccountId()).orElseThrow();

            locked[0] = isLocked("account", "account_id", accountA.getAccountId());
            locked[1] = isLocked("contest", "contest_id", sharedContest.getContestId());
            locked[2] = isLocked("member", "member_id", memberA.getMemberId());
            locked[3] = isLocked("account", "account_id", accountB.getAccountId());

            status.setRollbackOnly();
            return null;
        });

        System.out.println("=== findByIdWithLock 잠금 범위 ===");
        System.out.println("대상 계좌 : " + locked[0]);
        System.out.println("대회      : " + locked[1] + "   <- 모든 참가자가 공유하는 행");
        System.out.println("회원      : " + locked[2]);
        System.out.println("다른 계좌 : " + locked[3]);

        assertThat(locked[0]).as("대상 계좌 행은 잠긴다").isTrue();
        assertThat(locked[3]).as("다른 계좌 행은 잠기지 않는다").isFalse();

        // 대회 행이 풀린 것이 이 테스트의 핵심이다. 참가자 전원이 공유하는 행이라 잠기는 순간
        // 종목별 락으로 만든 병렬이 그 아래에서 전부 다시 직렬화된다.
        assertThat(locked[1]).as("대회 행은 잠기지 않는다").isFalse();
        assertThat(locked[2]).as("회원 행은 잠기지 않는다").isFalse();
    }

    // 별도 커넥션에서 NOWAIT 으로 잠가본다. 이미 잠겨 있으면 예외가 나므로 즉시 판정된다.
    // DataSource 에서 직접 받은 커넥션이라 바깥 트랜잭션에 참여하지 않는다.
    private boolean isLocked(String table, String idColumn, Long id) {
        String sql = "SELECT 1 FROM " + table + " WHERE " + idColumn + " = ? FOR UPDATE NOWAIT";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, id);
                statement.executeQuery();
                connection.rollback();
                return false;
            } catch (SQLException e) {
                connection.rollback();
                return true;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("잠금 탐침 실패: " + table, e);
        }
    }
}
