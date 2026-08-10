package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.dto.PendingOrdersResponse;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderHoldStatus;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.ForbiddenException;
import grit.stockIt.global.support.IntegrationTestSupport;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// OrderService Phase A 특성화: cancelOrder / getOrder / getPendingOrders / 인증 미보유 경로.
// 현재 관찰 가능한 동작(버그 의심 b, f 포함)을 그대로 동결한다. 프로덕션 코드는 수정하지 않는다.
// 격리 경화: 이 클래스는 OrderBookInvariantCharacterizationTest와 동일한
// @SpyBean(redisOrderBookRepository/orderSubscriptionCoordinator) 구성이라 스프링이 캐시된
// ApplicationContext(=동일 spy 싱글턴)를 재사용한다. @BeforeEach의 Mockito.reset()만으로는 형제
// 클래스 간 invocation 누출을 완전히 배제할 수 없으므로, 클래스 종료 시 컨텍스트를 폐기해 다음
// 클래스가 항상 새 spy 인스턴스를 받도록 구조적으로 격리한다(느리지만 결정적).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("OrderService 취소·조회·권한 특성화 테스트 (통합 테스트)")
class OrderCancelQueryCharacterizationTest extends IntegrationTestSupport {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderHoldRepository orderHoldRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private AccountStockRepository accountStockRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private ContestRepository contestRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @SpyBean
    private RedisOrderBookRepository redisOrderBookRepository;

    @SpyBean
    private OrderSubscriptionCoordinator orderSubscriptionCoordinator;

    private Member member;
    private Account account;
    private Stock stock;

    @BeforeEach
    void setUp() {
        // @SpyBean은 캐시된 컨텍스트에서 테스트 간 공유되므로, 각 테스트 시작 시 호출기록을 초기화한다(격리).
        org.mockito.Mockito.reset(redisOrderBookRepository, orderSubscriptionCoordinator);
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);

        member = memberRepository.save(Member.builder()
                .name("특성화 사용자 " + uniqueId)
                .email("cancel-query-" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());

        Contest contest = contestRepository.save(Contest.builder()
                .contestName("특성화 대회 " + uniqueId)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(BigDecimal.ZERO)
                .isDefault(false)
                .build());

        account = accountRepository.save(Account.builder()
                .member(member)
                .contest(contest)
                .accountName("특성화 계좌 " + uniqueId)
                .cash(new BigDecimal("100000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());

        stock = stockRepository.save(Stock.builder()
                .code("T" + uniqueId)
                .name("특성화 종목 " + uniqueId)
                .marketType("KOSPI")
                .build());

        authenticateAs(member.getEmail());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of()));
    }

    private Order saveLimitOrder(Account acc, Stock stk, OrderMethod method, BigDecimal price, int quantity) {
        return orderRepository.save(Order.createLimitOrder(acc, stk, price, quantity, method));
    }

    private Order saveMarketOrder(Account acc, Stock stk, OrderMethod method, int quantity) {
        return orderRepository.save(Order.createMarketOrder(acc, stk, quantity, method));
    }

    // BUY 홀딩 구성: Account.holdAmount 증가 + OrderHold 저장 (createLimitOrder 성공 경로 재현)
    // OrderHold의 @MapsId(Order)가 같은 영속성 컨텍스트 내 관리 상태의 order를 요구하므로
    // 단일 트랜잭션 안에서 재조회 후 생성한다.
    private void grantBuyHold(Order order, Account acc, BigDecimal holdAmount) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            Account managedAccount = accountRepository.findById(acc.getAccountId()).orElseThrow();
            Order managedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();
            managedAccount.increaseHoldAmount(holdAmount);
            accountRepository.save(managedAccount);
            orderHoldRepository.save(OrderHold.create(managedOrder, managedAccount, holdAmount));
            transactionManager.commit(status);
        } catch (RuntimeException e) {
            transactionManager.rollback(status);
            throw e;
        }
    }

    // SELL 홀딩 구성: AccountStock.holdQuantity 증가 (applySellHold 재현)
    private AccountStock grantSellHold(Account acc, Stock stk, int quantity, BigDecimal price) {
        AccountStock accountStock = accountStockRepository.findByAccountAndStock(acc, stk)
                .orElseGet(() -> accountStockRepository.save(AccountStock.create(acc, stk, quantity, price)));
        accountStock.increaseHoldQuantity(quantity);
        return accountStockRepository.save(accountStock);
    }

    private void forceCreatedAt(Long orderId, LocalDateTime createdAt) {
        jdbcTemplate.update("UPDATE trade_order SET created_at = ? WHERE order_id = ?",
                Timestamp.valueOf(createdAt), orderId);
    }

    // 15. PENDING(remaining>0) BUY 취소 — status=CANCELLED, releaseBuyHold로 Account.holdAmount 감소 + OrderHold.release
    @Test
    @DisplayName("PENDING BUY 취소 시 홀딩 금액이 해제되고 CANCELLED로 전이한다")
    void cancelOrder_pendingBuy_releasesHoldAndCancels() {
        BigDecimal price = new BigDecimal("10000");
        Order order = saveLimitOrder(account, stock, OrderMethod.BUY, price, 5);
        BigDecimal holdAmount = price.multiply(BigDecimal.valueOf(5));
        grantBuyHold(order, account, holdAmount);

        OrderResponse response = orderService.cancelOrder(order.getOrderId());

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        Order reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        Account reloadedAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloadedAccount.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        OrderHold reloadedHold = orderHoldRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(reloadedHold.getStatus()).isEqualTo(OrderHoldStatus.RELEASED);
        assertThat(reloadedHold.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // 버그 f 동결: remaining>0 취소 시 removeOrder/unregisterLimitOrder가 커밋 전 동기 호출된다.
        verify(redisOrderBookRepository, times(1)).removeOrder(order.getOrderId(), stock.getCode(), OrderMethod.BUY);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stock.getCode());
    }

    // 16. PENDING(remaining>0) SELL 취소 — releaseSellHold로 AccountStock.holdQuantity 감소
    @Test
    @DisplayName("PENDING SELL 취소 시 보유주식 홀딩 수량이 해제되고 CANCELLED로 전이한다")
    void cancelOrder_pendingSell_releasesHoldAndCancels() {
        BigDecimal price = new BigDecimal("10000");
        Order order = saveLimitOrder(account, stock, OrderMethod.SELL, price, 5);
        grantSellHold(account, stock, 5, price);

        OrderResponse response = orderService.cancelOrder(order.getOrderId());

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        AccountStock reloadedAccountStock = accountStockRepository.findByAccountAndStock(account, stock).orElseThrow();
        assertThat(reloadedAccountStock.getHoldQuantity()).isEqualTo(0);

        // 버그 f 동결: remaining>0 취소 시 removeOrder/unregisterLimitOrder가 커밋 전 동기 호출된다.
        verify(redisOrderBookRepository, times(1)).removeOrder(order.getOrderId(), stock.getCode(), OrderMethod.SELL);
        verify(orderSubscriptionCoordinator, times(1)).unregisterLimitOrder(stock.getCode());
    }

    // 17. PARTIALLY_FILLED 취소 — remaining>0 경로 동일, 부분수량 반영 동결
    @Test
    @DisplayName("PARTIALLY_FILLED 주문 취소 시 남은 수량만큼만 홀딩이 해제된다")
    void cancelOrder_partiallyFilledSell_releasesRemainingHoldOnly() {
        BigDecimal price = new BigDecimal("10000");
        Order order = saveLimitOrder(account, stock, OrderMethod.SELL, price, 10);
        grantSellHold(account, stock, 10, price);

        order.applyFill(4);
        orderRepository.save(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        OrderResponse response = orderService.cancelOrder(order.getOrderId());

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        Order reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(reloadedOrder.getFilledQuantity()).isEqualTo(4);
        assertThat(reloadedOrder.getRemainingQuantity()).isEqualTo(6);

        AccountStock reloadedAccountStock = accountStockRepository.findByAccountAndStock(account, stock).orElseThrow();
        // 최초 홀딩 10 - 취소 시점 잔여수량 6 해제 = 4 남음 (부분체결분은 이미 홀딩에서 빠지지 않은 현재 동작 동결)
        assertThat(reloadedAccountStock.getHoldQuantity()).isEqualTo(4);
    }

    // 18. remaining<=0 취소경로 — removeOrder/unregister 가드, releaseSellHold early return, status=CANCELLED
    @Test
    @DisplayName("remaining이 0인 주문을 취소해도 홀딩 해제 없이 CANCELLED로 전이한다")
    void cancelOrder_zeroRemaining_earlyReturnsHoldReleaseButStillCancels() {
        BigDecimal price = new BigDecimal("10000");
        Order order = saveLimitOrder(account, stock, OrderMethod.SELL, price, 5);
        grantSellHold(account, stock, 5, price);

        // 데이터 이상 상태(잔여수량 0인데 상태는 PENDING) 재현: applyFill은 FILLED로 전이시키므로
        // remaining<=0 취소경로(현재 status 체크에서 걸러지지 않는 경우)를 직접 구성한다.
        jdbcTemplate.update("UPDATE trade_order SET filled_quantity = quantity WHERE order_id = ?", order.getOrderId());

        OrderResponse response = orderService.cancelOrder(order.getOrderId());

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        Order reloadedOrder = orderRepository.findById(order.getOrderId()).orElseThrow();
        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(reloadedOrder.getRemainingQuantity()).isEqualTo(0);

        AccountStock reloadedAccountStock = accountStockRepository.findByAccountAndStock(account, stock).orElseThrow();
        assertThat(reloadedAccountStock.getHoldQuantity()).isEqualTo(5);

        // remaining<=0 가드 동결: removeOrder/unregisterLimitOrder는 호출되지 않는다.
        verify(redisOrderBookRepository, never()).removeOrder(order.getOrderId(), stock.getCode(), OrderMethod.SELL);
        verify(orderSubscriptionCoordinator, never()).unregisterLimitOrder(stock.getCode());
    }

    // 19. 이미 취소된 주문
    @Test
    @DisplayName("이미 취소된 주문을 다시 취소하면 BadRequestException이 발생한다")
    void cancelOrder_alreadyCancelled_throwsBadRequest() {
        Order order = saveLimitOrder(account, stock, OrderMethod.SELL, new BigDecimal("10000"), 3);
        order.markCancelled();
        orderRepository.save(order);

        assertThatThrownBy(() -> orderService.cancelOrder(order.getOrderId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 취소된 주문입니다.");

        // 상태 가드에서 조기 예외 발생 → 오더북/구독 코디네이터 미호출 동결.
        verify(redisOrderBookRepository, never()).removeOrder(order.getOrderId(), stock.getCode(), OrderMethod.SELL);
        verify(orderSubscriptionCoordinator, never()).unregisterLimitOrder(stock.getCode());
    }

    // 20. 이미 체결된 주문
    @Test
    @DisplayName("이미 체결된 주문을 취소하면 BadRequestException이 발생한다")
    void cancelOrder_alreadyFilled_throwsBadRequest() {
        Order order = saveLimitOrder(account, stock, OrderMethod.SELL, new BigDecimal("10000"), 3);
        order.applyFill(3);
        orderRepository.save(order);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);

        assertThatThrownBy(() -> orderService.cancelOrder(order.getOrderId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 체결된 주문은 취소할 수 없습니다.");

        // 상태 가드에서 조기 예외 발생 → 오더북/구독 코디네이터 미호출 동결.
        verify(redisOrderBookRepository, never()).removeOrder(order.getOrderId(), stock.getCode(), OrderMethod.SELL);
        verify(orderSubscriptionCoordinator, never()).unregisterLimitOrder(stock.getCode());
    }

    // 21. 주문 미존재
    @Test
    @DisplayName("존재하지 않는 주문을 취소하면 BadRequestException이 발생한다")
    void cancelOrder_notFound_throwsBadRequest() {
        assertThatThrownBy(() -> orderService.cancelOrder(999_999_999L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("주문을 찾을 수 없습니다.");
    }

    // 22. BUY 취소 시 OrderHold 미존재 — 버그 b 현동작 동결: Account.holdAmount 감소 없음
    @Test
    @DisplayName("[버그 b 동결] OrderHold가 없는 BUY 주문을 취소해도 Account.holdAmount는 감소하지 않는다")
    void cancelOrder_buyWithoutOrderHold_doesNotReleaseAccountHold() {
        BigDecimal price = new BigDecimal("10000");
        Order order = saveLimitOrder(account, stock, OrderMethod.BUY, price, 5);
        BigDecimal holdAmount = price.multiply(BigDecimal.valueOf(5));
        account.increaseHoldAmount(holdAmount);
        accountRepository.save(account);
        // 의도적으로 OrderHold 미생성 (홀딩 데이터 유실 재현)

        OrderResponse response = orderService.cancelOrder(order.getOrderId());

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);

        Account reloadedAccount = accountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloadedAccount.getHoldAmount()).isEqualByComparingTo(holdAmount);
        assertThat(orderHoldRepository.findById(order.getOrderId())).isEmpty();
    }

    // 24. getOrder 성공 / 타인계좌 Forbidden / 미존재 BadRequest
    @Test
    @DisplayName("getOrder 성공 시 OrderResponse를 반환한다")
    void getOrder_success_returnsOrderResponse() {
        BigDecimal price = new BigDecimal("12000");
        Order order = saveLimitOrder(account, stock, OrderMethod.BUY, price, 7);

        OrderResponse response = orderService.getOrder(order.getOrderId());

        assertThat(response.orderId()).isEqualTo(order.getOrderId());
        assertThat(response.accountId()).isEqualTo(account.getAccountId());
        assertThat(response.stockCode()).isEqualTo(stock.getCode());
        assertThat(response.stockName()).isEqualTo(stock.getName());
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.price()).isEqualByComparingTo(price);
        assertThat(response.quantity()).isEqualTo(7);
        assertThat(response.filledQuantity()).isEqualTo(0);
        assertThat(response.remainingQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("getOrder는 타인 계좌 주문 조회 시 ForbiddenException을 발생시킨다")
    void getOrder_otherAccountOrder_throwsForbidden() {
        String otherUniqueId = UUID.randomUUID().toString().substring(0, 8);
        Member otherMember = memberRepository.save(Member.builder()
                .name("타인 " + otherUniqueId)
                .email("cancel-query-other-" + otherUniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build());
        Contest otherContest = contestRepository.save(Contest.builder()
                .contestName("타인 대회 " + otherUniqueId)
                .startDate(LocalDateTime.now())
                .seedMoney(10_000_000L)
                .commissionRate(BigDecimal.ZERO)
                .isDefault(false)
                .build());
        Account otherAccount = accountRepository.save(Account.builder()
                .member(otherMember)
                .contest(otherContest)
                .accountName("타인 계좌 " + otherUniqueId)
                .cash(new BigDecimal("100000000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build());

        Order otherOrder = saveLimitOrder(otherAccount, stock, OrderMethod.BUY, new BigDecimal("10000"), 1);

        assertThatThrownBy(() -> orderService.getOrder(otherOrder.getOrderId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("해당 계좌에 대한 권한이 없습니다.");
    }

    @Test
    @DisplayName("getOrder는 존재하지 않는 주문 조회 시 BadRequestException을 발생시킨다")
    void getOrder_notFound_throwsBadRequest() {
        assertThatThrownBy(() -> orderService.getOrder(999_999_999L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("주문을 찾을 수 없습니다.");
    }

    // 25. getPendingOrders — PENDING/PARTIALLY_FILLED만, MARKET price=null 매핑, 개수/순서 동결, 계좌 미존재 BadRequest
    @Test
    @DisplayName("getPendingOrders는 PENDING/PARTIALLY_FILLED 주문만 최신순으로 반환하고 MARKET 가격은 null이다")
    void getPendingOrders_returnsOnlyActiveOrdersInCreatedDescOrderWithMarketPriceNull() {
        BigDecimal price = new BigDecimal("10000");

        Order oldestPending = saveLimitOrder(account, stock, OrderMethod.BUY, price, 2);
        forceCreatedAt(oldestPending.getOrderId(), LocalDateTime.now().minusMinutes(3));

        Order middlePartial = saveLimitOrder(account, stock, OrderMethod.SELL, price, 6);
        middlePartial.applyFill(2);
        orderRepository.save(middlePartial);
        forceCreatedAt(middlePartial.getOrderId(), LocalDateTime.now().minusMinutes(2));

        Order newestMarket = saveMarketOrder(account, stock, OrderMethod.SELL, 4);
        forceCreatedAt(newestMarket.getOrderId(), LocalDateTime.now().minusMinutes(1));

        Order filledOrder = saveLimitOrder(account, stock, OrderMethod.BUY, price, 1);
        filledOrder.applyFill(1);
        orderRepository.save(filledOrder);

        Order cancelledOrder = saveLimitOrder(account, stock, OrderMethod.BUY, price, 1);
        cancelledOrder.markCancelled();
        orderRepository.save(cancelledOrder);

        PendingOrdersResponse response = orderService.getPendingOrders(account.getAccountId());

        assertThat(response.orders()).hasSize(3);
        assertThat(response.orders())
                .extracting(PendingOrdersResponse.PendingOrderItem::orderId)
                .containsExactly(newestMarket.getOrderId(), middlePartial.getOrderId(), oldestPending.getOrderId());

        PendingOrdersResponse.PendingOrderItem marketItem = response.orders().get(0);
        assertThat(marketItem.price()).isNull();
        assertThat(marketItem.quantity()).isEqualTo(4);
        assertThat(marketItem.remainingQuantity()).isEqualTo(4);

        PendingOrdersResponse.PendingOrderItem partialItem = response.orders().get(1);
        assertThat(partialItem.price()).isEqualByComparingTo(price);
        assertThat(partialItem.remainingQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("getPendingOrders는 존재하지 않는 계좌 조회 시 BadRequestException을 발생시킨다")
    void getPendingOrders_accountNotFound_throwsBadRequest() {
        assertThatThrownBy(() -> orderService.getPendingOrders(999_999_999L))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("계좌를 찾을 수 없습니다.");
    }

    // 26. 권한 미인증 — SecurityContext anonymous/null → Forbidden('로그인이 필요합니다')
    @Test
    @DisplayName("인증 정보가 없으면(SecurityContext 비어있음) ForbiddenException이 발생한다")
    void getOrder_noAuthentication_throwsForbidden() {
        Order order = saveLimitOrder(account, stock, OrderMethod.BUY, new BigDecimal("10000"), 1);
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> orderService.getOrder(order.getOrderId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("로그인이 필요합니다.");
    }

    @Test
    @DisplayName("anonymousUser 인증이면 ForbiddenException이 발생한다")
    void getOrder_anonymousAuthentication_throwsForbidden() {
        Order order = saveLimitOrder(account, stock, OrderMethod.BUY, new BigDecimal("10000"), 1);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));

        assertThatThrownBy(() -> orderService.getOrder(order.getOrderId()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("로그인이 필요합니다.");
    }
}
