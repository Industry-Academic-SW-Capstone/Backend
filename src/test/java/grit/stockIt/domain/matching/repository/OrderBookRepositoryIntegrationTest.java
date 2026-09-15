package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// fetchMatchingEntries 의 가격 우선·시간 우선과 절단(LIMIT) 계약을 고정한다.
// 이 계약은 LimitOrderMatchPlanner.sortByPriority 와 공동 소유다 — 조회가 이미 정렬해 오고
// 플래너가 같은 기준으로 한 번 더 정렬한다.
@DisplayName("OrderBookRepository.fetchMatchingEntries 통합 테스트")
class OrderBookRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private OrderBookRepository orderBookRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private OrderRepository orderRepository;

    private Account testAccount;
    private Stock testStock;
    private String stockCode;

    @BeforeEach
    void setUp() {
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("테스트 사용자 " + uniqueId)
                .email("test" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());

        Contest contest = contestRepository.save(Contest.builder()
                .contestName("테스트 대회 " + uniqueId)
                .startDate(LocalDateTime.now())
                .seedMoney(10000000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());

        testAccount = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("테스트 계좌 " + uniqueId)
                .cash(new BigDecimal("1000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());

        stockCode = "005930-" + uniqueId;
        testStock = stockRepository.save(Stock.builder()
                .code(stockCode)
                .name("삼성전자")
                .build());
    }

    // 주문을 저장하면 그것으로 오더북 등록이 끝난다. 별도 색인이 없다.
    private Order saveOrder(OrderMethod method, BigDecimal price, int quantity) {
        return orderRepository.save(Order.createLimitOrder(testAccount, testStock, price, quantity, method));
    }

    @Test
    @DisplayName("BUY 테이커는 SELL 후보를 가장 저렴한 가격부터 조회한다")
    void fetchMatchingEntries_buyTaker_returnsSellCandidatesCheapestFirst() {
        saveOrder(OrderMethod.SELL, new BigDecimal("105"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("100"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("102"), 10);

        List<OrderBookEntry> entries = orderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("105"), 100);

        assertThat(entries).extracting(entry -> entry.price().intValueExact())
                .containsExactly(100, 102, 105);
    }

    @Test
    @DisplayName("SELL 테이커는 BUY 후보를 가장 비싼 가격부터 조회한다")
    void fetchMatchingEntries_sellTaker_returnsBuyCandidatesPriciestFirst() {
        saveOrder(OrderMethod.BUY, new BigDecimal("95"), 10);
        saveOrder(OrderMethod.BUY, new BigDecimal("100"), 10);
        saveOrder(OrderMethod.BUY, new BigDecimal("98"), 10);

        List<OrderBookEntry> entries = orderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.SELL, new BigDecimal("95"), 100);

        assertThat(entries).extracting(entry -> entry.price().intValueExact())
                .containsExactly(100, 98, 95);
    }

    @Test
    @DisplayName("체결가를 넘는 후보는 조회 대상에서 제외된다")
    void fetchMatchingEntries_excludesCandidatesBeyondPriceLimit() {
        saveOrder(OrderMethod.SELL, new BigDecimal("100"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("101"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("102"), 10);

        List<OrderBookEntry> entries = orderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("101"), 100);

        assertThat(entries).extracting(entry -> entry.price().intValueExact())
                .containsExactly(100, 101);
    }

    @Test
    @DisplayName("maxOrders는 임의의 N이 아니라 최우선 N개만 남기고 절단한다")
    void fetchMatchingEntries_maxOrders_keepsBestPricedNotArbitraryN() {
        saveOrder(OrderMethod.SELL, new BigDecimal("104"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("102"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("100"), 10); // best (가장 저렴)
        saveOrder(OrderMethod.SELL, new BigDecimal("103"), 10);
        saveOrder(OrderMethod.SELL, new BigDecimal("101"), 10);

        List<OrderBookEntry> entries = orderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("104"), 2);

        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(entry -> entry.price().intValueExact())
                .containsExactly(100, 101);
    }

    @Test
    @DisplayName("잔여 수량이 없는 주문은 별도 삭제 없이 오더북에서 빠진다")
    void fetchMatchingEntries_excludesFullyFilledOrders() {
        Order filled = saveOrder(OrderMethod.SELL, new BigDecimal("100"), 10);
        filled.applyFill(10);
        orderRepository.save(filled);
        saveOrder(OrderMethod.SELL, new BigDecimal("101"), 10);

        List<OrderBookEntry> entries = orderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("105"), 100);

        assertThat(entries).extracting(entry -> entry.price().intValueExact())
                .containsExactly(101);
        assertThat(orderBookRepository.exists(filled.getOrderId(), stockCode, OrderMethod.SELL)).isFalse();
    }
}
