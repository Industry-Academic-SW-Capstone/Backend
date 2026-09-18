package grit.stockIt.domain.settlement;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.settlement.entity.Settlement;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 멱등성 — 같은 체결은 한 번만 정산된다 (통합 테스트)")
class SettlementIdempotencyTest extends IntegrationTestSupport {

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ContestRepository contestRepository;

    @Test
    @DisplayName("같은 체결을 두 번 정산하면 DB 가 거부한다")
    void duplicateExecutionId_isRejectedByDatabase() {
        Execution execution = createExecution();

        settlementRepository.saveAndFlush(settlementOf(execution));

        assertThatThrownBy(() -> settlementRepository.saveAndFlush(settlementOf(execution)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("다른 체결은 각각 정산된다")
    void differentExecutions_areSettledSeparately() {
        Execution first = createExecution();
        Execution second = createExecution();

        settlementRepository.saveAndFlush(settlementOf(first));
        settlementRepository.saveAndFlush(settlementOf(second));

        assertThat(settlementRepository.existsByExecutionId(first.getExecutionId())).isTrue();
        assertThat(settlementRepository.existsByExecutionId(second.getExecutionId())).isTrue();
    }

    // 외래키를 걸어 두었으므로 실제로 존재하는 체결이어야 한다. 임의의 숫자를 쓰면
    // 운영 스키마에서만 깨지는 테스트가 된다.
    private Execution createExecution() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("정산테스트 " + suffix)
                .email("settlement-" + suffix + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("정산테스트 대회 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Account account = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("정산테스트 계좌 " + suffix)
                .cash(new BigDecimal("1000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("T" + suffix)
                .name("정산테스트종목 " + suffix)
                .marketType("KOSPI")
                .build());
        Order order = orderRepository.save(
                Order.createLimitOrder(account, stock, new BigDecimal("100"), 10, OrderMethod.BUY));
        return executionRepository.save(Execution.of(order, new BigDecimal("100"), 10));
    }

    private Settlement settlementOf(Execution execution) {
        return Settlement.of(
                execution.getExecutionId(),
                execution.getAccount().getAccountId(),
                new BigDecimal("-1000.00"),
                10);
    }
}
