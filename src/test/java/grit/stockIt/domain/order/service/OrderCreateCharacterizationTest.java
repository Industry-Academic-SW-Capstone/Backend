package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.MarketOrderCreateRequest;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.dto.StockDetailResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.UntradeableStockException;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * OrderService 주문 생성(createLimitOrder/createMarketOrder) 특성화 테스트 (Phase A, 프로덕션 무수정).
 * 현재 관찰 가능한 동작을 그대로 동결한다. 버그로 보이는 지점도 수정하지 않고 현재 동작대로 단언한다.
 *
 * 컨테이너·프로파일 설정은 IntegrationTestSupport 싱글턴을 상속 — 자체 @Container 선언은
 * reuse 활성 환경에서 같은 해시의 공유 컨테이너를 클래스 종료 시 stop시켜, 캐시된 다른
 * 테스트 컨텍스트를 전멸시키는 원인이었다(동일 설정이라 동작은 그대로).
 */
@DisplayName("OrderService 주문 생성 특성화 테스트 (통합 테스트)")
class OrderCreateCharacterizationTest extends IntegrationTestSupport {

    @Autowired
    private OrderService orderService;

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

    @Autowired
    private RedisMarketDataRepository redisMarketDataRepository;

    @MockBean
    private StockDetailService stockDetailService;

    private String memberEmail;

    @BeforeEach
    void setUp() {
        memberEmail = "order-create-" + UUID.randomUUID() + "@test.com";
        // 기본적으로 거래 가능 종목으로 취급 (개별 테스트에서 필요 시 재정의)
        when(stockDetailService.getStockDetail(anyString()))
                .thenReturn(Mono.just(tradeableStockDetail()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ===== 픽스처 헬퍼 =====

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Member createMember(String email) {
        Member member = Member.builder()
                .name("주문생성테스트 " + uniqueSuffix())
                .email(email)
                .provider(AuthProvider.LOCAL)
                .build();
        return memberRepository.save(member);
    }

    private Contest createContest() {
        Contest contest = Contest.builder()
                .contestName("주문생성테스트 대회 " + uniqueSuffix())
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build();
        return contestRepository.save(contest);
    }

    private Account createAccount(Member member, Contest contest, BigDecimal cash) {
        Account account = Account.builder()
                .member(member)
                .contest(contest)
                .accountName("주문생성테스트 계좌 " + uniqueSuffix())
                .cash(cash)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build();
        return accountRepository.save(account);
    }

    private Stock createStock() {
        Stock stock = Stock.builder()
                .code("T" + uniqueSuffix())
                .name("특성화종목 " + uniqueSuffix())
                .marketType("KOSPI")
                .build();
        return stockRepository.save(stock);
    }

    /** email 소유자의 계좌 1개 + 종목 1개로 구성된 기본 시나리오 픽스처. */
    private record Fixture(Member member, Contest contest, Account account, Stock stock) {
    }

    private Fixture createFixture(BigDecimal cash) {
        Member member = createMember(memberEmail);
        Contest contest = createContest();
        Account account = createAccount(member, contest, cash);
        Stock stock = createStock();
        return new Fixture(member, contest, account, stock);
    }

    /** 동일 회원(memberEmail) 소유의 추가 계좌를 새 대회 하에 생성한다 (member 재생성으로 인한 email 유니크 제약 충돌 방지). */
    private Fixture createAdditionalFixture(Member member, BigDecimal cash) {
        Contest contest = createContest();
        Account account = createAccount(member, contest, cash);
        Stock stock = createStock();
        return new Fixture(member, contest, account, stock);
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private StockDetailResponse tradeableStockDetail() {
        return stockDetail(true, null);
    }

    private StockDetailResponse stockDetail(boolean tradeable, String untradeableReason) {
        return new StockDetailResponse(
                "IGNORED", "무시됨", 0, 0, "0", null,
                0L, 0L, 0L, 0.0, 0.0, 0.0,
                0, 0, 0, 0, 0,
                null, null,
                tradeable, untradeableReason,
                null, null
        );
    }

    private AccountStock createHolding(Account account, Stock stock, int quantity, BigDecimal avgPrice) {
        AccountStock accountStock = AccountStock.create(account, stock, quantity, avgPrice);
        return accountStockRepository.save(accountStock);
    }

    // ===== 1. 지정가 BUY 성공 =====

    @Test
    @DisplayName("createLimitOrder: BUY 성공 시 PENDING 주문·홀딩금액·OrderHold가 생성된다")
    void createLimitOrder_buySuccess() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        BigDecimal price = new BigDecimal("10000");
        int quantity = 5;
        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), price, quantity, OrderMethod.BUY);

        OrderResponse response = orderService.createLimitOrder(request);

        assertThat(response.orderId()).isNotNull();
        assertThat(response.accountId()).isEqualTo(fx.account().getAccountId());
        assertThat(response.stockCode()).isEqualTo(fx.stock().getCode());
        assertThat(response.orderType()).isEqualTo(OrderType.LIMIT);
        assertThat(response.orderMethod()).isEqualTo(OrderMethod.BUY);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.price()).isEqualByComparingTo(price);
        assertThat(response.quantity()).isEqualTo(quantity);
        assertThat(response.filledQuantity()).isZero();
        assertThat(response.remainingQuantity()).isEqualTo(quantity);

        Account updatedAccount = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        BigDecimal expectedHold = price.multiply(BigDecimal.valueOf(quantity));
        assertThat(updatedAccount.getHoldAmount()).isEqualByComparingTo(expectedHold);

        OrderHold hold = orderHoldRepository.findById(response.orderId()).orElseThrow();
        assertThat(hold.getHoldAmount()).isEqualByComparingTo(expectedHold);
        assertThat(hold.getAccount().getAccountId()).isEqualTo(fx.account().getAccountId());
    }

    // ===== 2. 지정가 SELL 성공 =====

    @Test
    @DisplayName("createLimitOrder: SELL 성공 시 AccountStock 홀딩수량만 증가하고 OrderHold는 생성되지 않는다")
    void createLimitOrder_sellSuccess() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        createHolding(fx.account(), fx.stock(), 10, new BigDecimal("9000"));
        authenticateAs(memberEmail);

        int quantity = 4;
        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), new BigDecimal("9500"), quantity, OrderMethod.SELL);

        OrderResponse response = orderService.createLimitOrder(request);

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.remainingQuantity()).isEqualTo(quantity);

        AccountStock updated = accountStockRepository.findByAccountAndStock(fx.account(), fx.stock()).orElseThrow();
        assertThat(updated.getHoldQuantity()).isEqualTo(quantity);

        assertThat(orderHoldRepository.findById(response.orderId())).isEmpty();
    }

    // ===== 3. BUY 현금부족 =====

    @Test
    @DisplayName("createLimitOrder: BUY 현금부족 시 BadRequestException, DB 무변경")
    void createLimitOrder_buyInsufficientCash() {
        Fixture fx = createFixture(new BigDecimal("100"));
        authenticateAs(memberEmail);

        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), new BigDecimal("10000"), 5, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createLimitOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("주문 가능 현금이 부족합니다.");

        Account unchanged = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(unchanged.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(unchanged.getCash()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(orderRepository.findByAccountIdAndStockCode(
                fx.account().getAccountId(), fx.stock().getCode(), true, OrderStatus.CANCELLED)).isEmpty();
    }

    // ===== 4. 미거래종목 =====

    @Test
    @DisplayName("createLimitOrder: BUY 미거래종목(tradeable=false) 시 UntradeableStockException(사유 포함)")
    void createLimitOrder_buyUntradeableStock() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);
        when(stockDetailService.getStockDetail(fx.stock().getCode()))
                .thenReturn(Mono.just(stockDetail(false, "AI 분석 결과 거래가 제한됩니다.")));

        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), new BigDecimal("10000"), 5, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createLimitOrder(request))
                .isInstanceOf(UntradeableStockException.class)
                .hasMessage("AI 분석 결과 거래가 제한됩니다.");
    }

    @Test
    @DisplayName("createLimitOrder: BUY 거래가능 조회 중 일반 예외 발생 시에도 UntradeableStockException으로 뭉뚱그려진다(버그 e 동결)")
    void createLimitOrder_buyStockDetailLookupFails_wrappedAsUntradeable() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);
        when(stockDetailService.getStockDetail(fx.stock().getCode()))
                .thenReturn(Mono.error(new RuntimeException("KIS 연결 실패")));

        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), new BigDecimal("10000"), 5, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createLimitOrder(request))
                .isInstanceOf(UntradeableStockException.class)
                .hasMessage("종목 거래 가능 여부를 확인할 수 없습니다.");
    }

    // ===== 5. orderMethod null =====

    @Test
    @DisplayName("createLimitOrder: orderMethod null 시 BadRequestException")
    void createLimitOrder_orderMethodNull() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), new BigDecimal("10000"), 5, null);

        assertThatThrownBy(() -> orderService.createLimitOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("매수/매도 구분이 필요합니다.");
    }

    // ===== 6. 계좌 미존재 =====

    @Test
    @DisplayName("createLimitOrder: 계좌 미존재 시 BadRequestException")
    void createLimitOrder_accountNotFound() {
        authenticateAs(memberEmail);
        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                -999L, "ANY", new BigDecimal("10000"), 5, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createLimitOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("계좌를 찾을 수 없습니다.");
    }

    // ===== 7. 종목 미존재 =====

    @Test
    @DisplayName("createLimitOrder: 종목 미존재 시 BadRequestException")
    void createLimitOrder_stockNotFound() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        LimitOrderCreateRequest request = new LimitOrderCreateRequest(
                fx.account().getAccountId(), "NOPE-" + uniqueSuffix(), new BigDecimal("10000"), 5, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createLimitOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("존재하지 않는 종목입니다.");
    }

    // ===== 8. 시장가 BUY 캐시적중 =====

    @Test
    @DisplayName("createMarketOrder: BUY 캐시적중 시 lastPrice*qty*(1+buffer)로 홀딩금액이 계산된다")
    void createMarketOrder_buyCacheHit() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        BigDecimal lastPrice = new BigDecimal("12345");
        int quantity = 3;
        redisMarketDataRepository.updateLastPrice(fx.stock().getCode(), lastPrice);

        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), quantity, OrderMethod.BUY);

        OrderResponse response = orderService.createMarketOrder(request);

        assertThat(response.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.remainingQuantity()).isEqualTo(quantity);

        BigDecimal expectedHold = lastPrice.multiply(BigDecimal.valueOf(quantity))
                .multiply(new BigDecimal("1.05"))
                .setScale(2, RoundingMode.UP);

        Account updated = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(updated.getHoldAmount()).isEqualByComparingTo(expectedHold);

        OrderHold hold = orderHoldRepository.findById(response.orderId()).orElseThrow();
        assertThat(hold.getHoldAmount()).isEqualByComparingTo(expectedHold);
    }

    // ===== 9. 시장가 BUY 캐시미스 -> KIS성공 =====

    @Test
    @DisplayName("createMarketOrder: BUY 캐시미스 시 KIS 현재가로 홀딩금액을 계산한다")
    void createMarketOrder_buyCacheMissKisSuccess() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        BigDecimal kisPrice = new BigDecimal("20000");
        int quantity = 2;
        when(stockDetailService.getCurrentPrice(fx.stock().getCode())).thenReturn(Mono.just(kisPrice));

        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), quantity, OrderMethod.BUY);

        OrderResponse response = orderService.createMarketOrder(request);

        BigDecimal expectedHold = kisPrice.multiply(BigDecimal.valueOf(quantity))
                .multiply(new BigDecimal("1.05"))
                .setScale(2, RoundingMode.UP);

        Account updated = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(updated.getHoldAmount()).isEqualByComparingTo(expectedHold);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    // ===== 10. 시장가 BUY 캐시미스 -> KIS실패 =====

    @Test
    @DisplayName("createMarketOrder: BUY 캐시미스 + KIS 실패 시 BadRequestException('최근 체결가 정보를 찾을 수 없습니다.')")
    void createMarketOrder_buyCacheMissKisFailure() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        when(stockDetailService.getCurrentPrice(fx.stock().getCode()))
                .thenReturn(Mono.error(new RuntimeException("KIS 오류")));

        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 2, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createMarketOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("최근 체결가 정보를 찾을 수 없습니다.");

        assertThat(orderRepository.findByAccountIdAndStockCode(
                fx.account().getAccountId(), fx.stock().getCode(), true, OrderStatus.CANCELLED)).isEmpty();
    }

    // ===== 11. 시장가 BUY 현금부족 =====

    @Test
    @DisplayName("createMarketOrder: BUY 현금부족 시 BadRequestException, 롤백된다")
    void createMarketOrder_buyInsufficientCash() {
        Fixture fx = createFixture(new BigDecimal("100"));
        authenticateAs(memberEmail);

        BigDecimal lastPrice = new BigDecimal("50000");
        redisMarketDataRepository.updateLastPrice(fx.stock().getCode(), lastPrice);

        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 5, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createMarketOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("주문 가능 현금이 부족합니다.");

        Account unchanged = accountRepository.findById(fx.account().getAccountId()).orElseThrow();
        assertThat(unchanged.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(orderRepository.findByAccountIdAndStockCode(
                fx.account().getAccountId(), fx.stock().getCode(), true, OrderStatus.CANCELLED)).isEmpty();
    }

    // ===== 12. 시장가 BUY 캐시적중 무효가 =====

    @Test
    @DisplayName("createMarketOrder: 캐시 최근가가 0 이하이면 BadRequestException('최근 체결가가 유효하지 않습니다.')")
    void createMarketOrder_buyCacheHitInvalidPrice() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        redisMarketDataRepository.updateLastPrice(fx.stock().getCode(), BigDecimal.ZERO);

        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 3, OrderMethod.BUY);

        assertThatThrownBy(() -> orderService.createMarketOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("최근 체결가가 유효하지 않습니다.");
    }

    // ===== 13. 시장가 SELL 성공 =====

    @Test
    @DisplayName("createMarketOrder: SELL 성공 시 AccountStock 홀딩수량이 증가한다")
    void createMarketOrder_sellSuccess() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        createHolding(fx.account(), fx.stock(), 10, new BigDecimal("9000"));
        authenticateAs(memberEmail);

        int quantity = 6;
        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), quantity, OrderMethod.SELL);

        OrderResponse response = orderService.createMarketOrder(request);

        assertThat(response.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);

        AccountStock updated = accountStockRepository.findByAccountAndStock(fx.account(), fx.stock()).orElseThrow();
        assertThat(updated.getHoldQuantity()).isEqualTo(quantity);
        assertThat(orderHoldRepository.findById(response.orderId())).isEmpty();
    }

    // ===== 14. 시장가 SELL 무보유 =====

    @Test
    @DisplayName("createMarketOrder: SELL 시 보유 종목이 없으면 BadRequestException('보유 중인 종목이 없습니다.')")
    void createMarketOrder_sellNoHolding() {
        Fixture fx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);

        MarketOrderCreateRequest request = new MarketOrderCreateRequest(
                fx.account().getAccountId(), fx.stock().getCode(), 3, OrderMethod.SELL);

        assertThatThrownBy(() -> orderService.createMarketOrder(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("보유 중인 종목이 없습니다.");
    }

    // ===== 23. applyBuyHold save 경계: 지정가/시장가 모두 OrderHold FK·금액 동결 =====

    @Test
    @DisplayName("applyBuyHold: 지정가·시장가 BUY 성공 시 OrderHold.orderId가 저장된 Order FK와 일치하고 금액이 정확하다")
    void applyBuyHold_saveBoundary_limitAndMarket() {
        // 지정가
        Fixture limitFx = createFixture(new BigDecimal("1000000"));
        authenticateAs(memberEmail);
        BigDecimal limitPrice = new BigDecimal("8000");
        int limitQty = 10;
        LimitOrderCreateRequest limitRequest = new LimitOrderCreateRequest(
                limitFx.account().getAccountId(), limitFx.stock().getCode(), limitPrice, limitQty, OrderMethod.BUY);
        OrderResponse limitResponse = orderService.createLimitOrder(limitRequest);

        assertThat(orderRepository.existsById(limitResponse.orderId())).isTrue();
        OrderHold limitHold = orderHoldRepository.findById(limitResponse.orderId()).orElseThrow();
        assertThat(limitHold.getOrderId()).isEqualTo(limitResponse.orderId());
        assertThat(limitHold.getOrder().getOrderId()).isEqualTo(limitResponse.orderId());
        BigDecimal expectedLimitHold = limitPrice.multiply(BigDecimal.valueOf(limitQty));
        assertThat(limitHold.getHoldAmount()).isEqualByComparingTo(expectedLimitHold);
        Account limitAccount = accountRepository.findById(limitFx.account().getAccountId()).orElseThrow();
        assertThat(limitAccount.getHoldAmount()).isEqualByComparingTo(expectedLimitHold);

        // 시장가 (별도 픽스처, 같은 인증 이메일)
        Fixture marketFx = createAdditionalFixture(limitFx.member(), new BigDecimal("1000000"));
        BigDecimal lastPrice = new BigDecimal("15000");
        int marketQty = 4;
        redisMarketDataRepository.updateLastPrice(marketFx.stock().getCode(), lastPrice);
        MarketOrderCreateRequest marketRequest = new MarketOrderCreateRequest(
                marketFx.account().getAccountId(), marketFx.stock().getCode(), marketQty, OrderMethod.BUY);
        OrderResponse marketResponse = orderService.createMarketOrder(marketRequest);

        assertThat(orderRepository.existsById(marketResponse.orderId())).isTrue();
        OrderHold marketHold = orderHoldRepository.findById(marketResponse.orderId()).orElseThrow();
        assertThat(marketHold.getOrderId()).isEqualTo(marketResponse.orderId());
        assertThat(marketHold.getOrder().getOrderId()).isEqualTo(marketResponse.orderId());
        BigDecimal expectedMarketHold = lastPrice.multiply(BigDecimal.valueOf(marketQty))
                .multiply(new BigDecimal("1.05"))
                .setScale(2, RoundingMode.UP);
        assertThat(marketHold.getHoldAmount()).isEqualByComparingTo(expectedMarketHold);
        Account marketAccount = accountRepository.findById(marketFx.account().getAccountId()).orElseThrow();
        assertThat(marketAccount.getHoldAmount()).isEqualByComparingTo(expectedMarketHold);
    }
}
