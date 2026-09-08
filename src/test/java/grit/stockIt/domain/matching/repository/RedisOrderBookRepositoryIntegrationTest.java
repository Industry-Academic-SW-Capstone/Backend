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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisOrderBookRepository#fetchMatchingEntries}의 Testcontainers 기반 통합 테스트.
 *
 * <p>순수 단위 테스트로는 검증할 수 없는, 실제 Redis ZSET의 방향 분기(rangeByScore vs
 * reverseRangeByScore)와 maxOrders 절단(truncation) 시맨틱을 검증하는 유일한 오라클이다.
 * ({@code LimitOrderMatchPlannerTest}는 이미 정렬·할당된 in-memory 목록만 다루므로 이
 * 경로를 커버하지 못한다.)
 */
@DisplayName("RedisOrderBookRepository.fetchMatchingEntries 통합 테스트")
class RedisOrderBookRepositoryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private RedisOrderBookRepository redisOrderBookRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("테스트 사용자 " + uniqueId)
                .email("test" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());

        Contest contest = contestRepository.save(Contest.builder()
                .contestName("테스트 대회 " + uniqueId)
                .startDate(java.time.LocalDateTime.now())
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

    private Order saveAndIndexOrder(OrderMethod method, BigDecimal price, int quantity) {
        Order order = Order.createLimitOrder(testAccount, testStock, price, quantity, method);
        order = orderRepository.save(order);
        redisOrderBookRepository.addOrder(order);
        return order;
    }

    @Test
    @DisplayName("BUY 테이커는 rangeByScore(오름차순)로 SELL 후보를 가장 저렴한 가격부터 조회한다")
    void fetchMatchingEntries_BuyTaker_FetchesSellCandidatesAscendingByRangeByScore() {
        // Given: SELL 주문 3개를 무작위 순서로 저장
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("105"), 10);
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("100"), 10);
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("102"), 10);

        // When: BUY 테이커가 가격 상한 105로 매칭 후보를 조회
        List<OrderBookEntry> entries = redisOrderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("105"), 100);

        // Then: 가격 오름차순 (가장 저렴한 것부터)
        assertThat(entries).extracting(OrderBookEntry::price)
                .containsExactly(new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("105"));
    }

    @Test
    @DisplayName("SELL 테이커는 reverseRangeByScore(내림차순)로 BUY 후보를 가장 비싼 가격부터 조회한다")
    void fetchMatchingEntries_SellTaker_FetchesBuyCandidatesDescendingByReverseRangeByScore() {
        // Given: BUY 주문 3개를 무작위 순서로 저장
        saveAndIndexOrder(OrderMethod.BUY, new BigDecimal("95"), 10);
        saveAndIndexOrder(OrderMethod.BUY, new BigDecimal("100"), 10);
        saveAndIndexOrder(OrderMethod.BUY, new BigDecimal("98"), 10);

        // When: SELL 테이커가 가격 하한 95로 매칭 후보를 조회
        List<OrderBookEntry> entries = redisOrderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.SELL, new BigDecimal("95"), 100);

        // Then: 가격 내림차순 (가장 비싼 것부터)
        assertThat(entries).extracting(OrderBookEntry::price)
                .containsExactly(new BigDecimal("100"), new BigDecimal("98"), new BigDecimal("95"));
    }

    @Test
    @DisplayName("maxOrders는 임의의 N이 아니라 최우선(best-priced) N개만 남기고 절단한다")
    void fetchMatchingEntries_MaxOrdersTruncation_KeepsBestPricedNNotArbitraryN() {
        // Given: SELL 주문 5개, 가격이 100~104
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("104"), 10);
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("102"), 10);
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("100"), 10); // best (가장 저렴)
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("103"), 10);
        saveAndIndexOrder(OrderMethod.SELL, new BigDecimal("101"), 10);

        // When: BUY 테이커, maxOrders=2 → 가장 저렴한 2개만 남아야 함(100, 101)
        List<OrderBookEntry> entries = redisOrderBookRepository.fetchMatchingEntries(
                stockCode, OrderMethod.BUY, new BigDecimal("104"), 2);

        // Then
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(OrderBookEntry::price)
                .containsExactly(new BigDecimal("100"), new BigDecimal("101"));
    }
}
