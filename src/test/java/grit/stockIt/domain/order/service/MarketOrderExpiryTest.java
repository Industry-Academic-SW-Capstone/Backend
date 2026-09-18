package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.MarketOrderCreateRequest;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.order.scheduler.MarketOrderExpiryScheduler;
import grit.stockIt.domain.stock.dto.StockDetailResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("시장가 미체결 주문 당일 만료 (통합 테스트)")
class MarketOrderExpiryTest extends IntegrationTestSupport {

    private static final BigDecimal UPPER_LIMIT_PRICE = new BigDecimal("91000");

    @Autowired
    private OrderService orderService;

    @Autowired
    private MarketOrderExpiryService marketOrderExpiryService;

    @Autowired
    private MarketOrderExpiryScheduler marketOrderExpiryScheduler;

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

    @Autowired
    private OrderHoldRepository orderHoldRepository;

    @Autowired
    private AccountStockRepository accountStockRepository;

    @MockitoBean
    private StockDetailService stockDetailService;

    private String memberEmail;

    @BeforeEach
    void setUp() {
        memberEmail = "market-expiry-" + UUID.randomUUID() + "@test.com";
        when(stockDetailService.getStockDetail(anyString())).thenReturn(Mono.just(tradeableStockDetail()));
        when(stockDetailService.getUpperLimitPrice(anyString())).thenReturn(Mono.just(UPPER_LIMIT_PRICE));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("미체결 시장가 매수 주문은 만료되어 취소되고 현금 홀딩이 풀린다")
    void expire_marketBuyOrder_releasesCashHold() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);
        OrderResponse created = orderService.createMarketOrder(new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 3, OrderMethod.BUY));

        Account beforeExpiry = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(beforeExpiry.getHoldAmount()).isEqualByComparingTo(UPPER_LIMIT_PRICE.multiply(BigDecimal.valueOf(3)));

        boolean expired = marketOrderExpiryService.expire(created.orderId());

        assertThat(expired).isTrue();
        Order order = orderRepository.findById(created.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Account account = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(account.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        OrderHold hold = orderHoldRepository.findById(created.orderId()).orElseThrow();
        assertThat(hold.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("미체결 시장가 매도 주문은 만료되어 보유 홀딩 수량이 풀린다")
    void expire_marketSellOrder_releasesHoldQuantity() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        accountStockRepository.save(AccountStock.create(fx.account(), fx.stock(), 10, new BigDecimal("50000")));
        authenticateAs(memberEmail);
        OrderResponse created = orderService.createMarketOrder(new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 4, OrderMethod.SELL));

        assertThat(holdingOf(fx).getHoldQuantity()).isEqualTo(4);

        marketOrderExpiryService.expire(created.orderId());

        assertThat(orderRepository.findById(created.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        AccountStock holding = holdingOf(fx);
        assertThat(holding.getHoldQuantity()).isZero();
        assertThat(holding.getQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("지정가 주문은 만료 대상이 아니다 — 주문가가 고정이라 홀딩이 다음 날에도 유효하다")
    void limitOrder_isNotExpirable() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);
        OrderResponse limitOrder = orderService.createLimitOrder(new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), new BigDecimal("10000"), 2, OrderMethod.BUY));
        OrderResponse marketOrder = orderService.createMarketOrder(new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 1, OrderMethod.BUY));

        List<Long> expirable = marketOrderExpiryService.findExpirableOrderIds();

        assertThat(expirable).contains(marketOrder.orderId());
        assertThat(expirable).doesNotContain(limitOrder.orderId());
    }

    @Test
    @DisplayName("스케줄러가 대상을 모두 만료시키며, 다시 돌려도 바뀌는 것이 없다")
    void scheduler_expiresAll_andIsIdempotent() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);
        OrderResponse first = orderService.createMarketOrder(new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 2, OrderMethod.BUY));
        OrderResponse second = orderService.createMarketOrder(new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 1, OrderMethod.BUY));

        marketOrderExpiryScheduler.expireMarketOrders();

        assertThat(orderRepository.findById(first.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(orderRepository.findById(second.orderId()).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(marketOrderExpiryService.findExpirableOrderIds()).isEmpty();

        marketOrderExpiryScheduler.expireMarketOrders();

        assertThat(accountRepository.findById(fx.account().getAccountId()).orElseThrow().getHoldAmount())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    private AccountStock holdingOf(Fixture fx) {
        return accountStockRepository.findByAccountAndStock(fx.account(), fx.stock()).orElseThrow();
    }

    private record Fixture(Member member, Contest contest, Account account, Stock stock) {
    }

    private Fixture createFixture(BigDecimal cash) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Member member = memberRepository.save(Member.builder()
                .name("만료테스트 " + suffix)
                .email(memberEmail)
                .provider(AuthProvider.LOCAL)
                .build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("만료테스트 대회 " + suffix)
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Account account = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("만료테스트 계좌 " + suffix)
                .cash(cash)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("T" + suffix)
                .name("만료테스트종목 " + suffix)
                .marketType("KOSPI")
                .build());
        return new Fixture(member, contest, account, stock);
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private StockDetailResponse tradeableStockDetail() {
        return new StockDetailResponse(
                "IGNORED", "무시됨", 0, 0, "0", null,
                0L, 0L, 0L, 0.0, 0.0, 0.0,
                0, 0, 0, 0, 0,
                null, null,
                true, null,
                null, null
        );
    }
}
