package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.MarketOrderCreateRequest;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.support.IntegrationTestSupport;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 컨테이너·프로파일 설정은 IntegrationTestSupport 싱글턴을 상속.
// 오더북(afterCommit) 불변식은 실 커밋에 의존하므로 테스트 메서드에 @Transactional을 붙이지 않는다
// (스프링 @Transactional 롤백 테스트는 afterCommit 콜백을 발화시키지 않아 계획에서 금지됨).
@DisplayName("OrderService 오더북(afterCommit) 불변식 특성화 테스트 (Phase A, 프로덕션 무수정)")
class OrderBookInvariantCharacterizationTest extends IntegrationTestSupport {

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

    @SpyBean
    private RedisOrderBookRepository redisOrderBookRepository;

    @SpyBean
    private OrderSubscriptionCoordinator orderSubscriptionCoordinator;

    @MockBean
    private StockDetailService stockDetailService;

    private Member testMember;
    private Contest testContest;

    @BeforeEach
    void setUp() {
        // @SpyBean은 캐시된 컨텍스트에서 테스트 간 공유되므로, 각 테스트 시작 시 stub·호출기록을 초기화해
        // 이전 테스트의 addOrder/register 호출이나 doThrow 스텁이 누출되지 않게 한다(격리).
        org.mockito.Mockito.reset(redisOrderBookRepository, orderSubscriptionCoordinator);
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        testMember = Member.builder()
                .name("테스트 사용자 " + uniqueId)
                .email("test" + uniqueId + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build();
        testMember = memberRepository.save(testMember);

        testContest = Contest.builder()
                .contestName("테스트 대회 " + uniqueId)
                .startDate(java.time.LocalDateTime.now())
                .seedMoney(10000000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build();
        testContest = contestRepository.save(testContest);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(testMember.getEmail(), null, List.of())
        );

        // BUY 주문은 매수 시 항상 validateStockTradeable(getStockDetail)을 먼저 통과해야
        // 이후 현금 검증/afterCommit 경로에 도달한다. 기본적으로 거래 가능으로 스텁.
        org.mockito.Mockito.when(stockDetailService.getStockDetail(anyString()))
                .thenReturn(Mono.just(tradeableStockDetail()));
    }

    private grit.stockIt.domain.stock.dto.StockDetailResponse tradeableStockDetail() {
        return new grit.stockIt.domain.stock.dto.StockDetailResponse(
                "000000", "테스트", 100, 0, "0", null,
                0L, 0L, 0L, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0,
                null, null, true, null, null, null
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Account createAccount(BigDecimal cash) {
        Account account = Account.builder()
                .member(testMember)
                .contest(testContest)
                .accountName("테스트 계좌 " + UUID.randomUUID())
                .cash(cash)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build();
        return accountRepository.save(account);
    }

    private Stock createStock() {
        String code = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
        Stock stock = Stock.builder()
                .code(code)
                .name("테스트종목-" + code)
                .build();
        return stockRepository.save(stock);
    }

    // ===== 27a: 정상 커밋 → 오더북 반영 =====
    @Test
    @DisplayName("27a. 지정가 주문 정상 커밋 시 afterCommit 훅에서 addOrder가 1회 호출되어 오더북에 반영된다")
    void afterCommit_normalCommit_addsOrderToOrderBook() {
        // given: 매수 주문에 필요한 충분한 현금을 가진 계좌
        Account account = createAccount(new BigDecimal("1000000"));
        Stock stock = createStock();
        var request = new LimitOrderCreateRequest(account.getAccountId(), stock.getCode(),
                new BigDecimal("100"), 10, OrderMethod.BUY);

        // when
        var response = orderService.createLimitOrder(request);

        // then: afterCommit이 커밋 시점에 동기 발화되어 addOrder가 정확히 1회 호출됨
        verify(redisOrderBookRepository, times(1)).addOrder(any(Order.class));
        assertThat(redisOrderBookRepository.exists(response.orderId(), stock.getCode(), OrderMethod.BUY)).isTrue();
    }

    // ===== 27b: 강제(예외 기반) 롤백 → 오더북 미반영 =====
    @Test
    @DisplayName("27b. 현금 부족으로 트랜잭션이 예외 롤백되면 afterCommit이 발화되지 않아 addOrder가 호출되지 않는다")
    void afterCommit_exceptionBasedRollback_neverAddsOrderToOrderBook() {
        // given: 홀딩 가능 금액보다 부족한 현금 계좌 (BadRequestException 유발용, @Transactional 롤백 테스트 아님)
        Account account = createAccount(new BigDecimal("1"));
        Stock stock = createStock();
        var request = new LimitOrderCreateRequest(account.getAccountId(), stock.getCode(),
                new BigDecimal("100"), 10, OrderMethod.BUY);

        // when / then: ensureSufficientCash에서 BadRequestException → 트랜잭션 전체 롤백
        org.junit.jupiter.api.Assertions.assertThrows(BadRequestException.class,
                () -> orderService.createLimitOrder(request));

        // then: afterCommit이 등록조차 되지 않으므로(예외가 save 이전에 발생) addOrder 미호출
        verify(redisOrderBookRepository, never()).addOrder(any(Order.class));
        List<Order> orders = orderRepository.findAllPendingOrdersByAccountId(
                account.getAccountId(),
                List.of(grit.stockIt.domain.order.entity.OrderStatus.PENDING)
        );
        assertThat(orders).isEmpty();
    }

    // ===== 27c: addOrder 예외 → DB 커밋 유지, 오더북 미반영, 예외 미전파 =====
    @Test
    @DisplayName("27c. afterCommit 훅 내 addOrder가 예외를 던져도 주문은 DB에 커밋되고 예외는 삼켜지며 오더북은 미반영된다")
    void afterCommit_addOrderThrows_orderStillCommitted_exceptionSwallowed() {
        // given
        Account account = createAccount(new BigDecimal("1000000"));
        Stock stock = createStock();
        var request = new LimitOrderCreateRequest(account.getAccountId(), stock.getCode(),
                new BigDecimal("100"), 10, OrderMethod.BUY);

        doThrow(new RuntimeException("simulated redis addOrder failure"))
                .when(redisOrderBookRepository).addOrder(any(Order.class));

        // when: addOrderToRedisAfterCommit의 catch(Exception)가 예외를 삼키므로 서비스 호출은 정상 반환됨
        var response = orderService.createLimitOrder(request);

        // then: 주문은 DB에 커밋되어 조회 가능
        Order saved = orderRepository.findById(response.orderId()).orElseThrow();
        assertThat(saved.getOrderId()).isEqualTo(response.orderId());

        // then: addOrder는 호출되었으나(예외 발생) 오더북에는 실제로 반영되지 않음
        verify(redisOrderBookRepository, times(1)).addOrder(any(Order.class));
        assertThat(redisOrderBookRepository.exists(response.orderId(), stock.getCode(), OrderMethod.BUY)).isFalse();

        // 특이사항: addOrderToRedisAfterCommit의 try 블록에서 addOrder 예외 시 이후의
        // registerLimitOrder(stock.getCode()) 호출도 함께 스킵된다 (현재 동작 그대로 동결).
        verify(orderSubscriptionCoordinator, never()).registerLimitOrder(stock.getCode());
    }

    // ===== 28: 시장가 KIS 실패 롤백 후 선구독 잔존 (버그 의심 a) =====
    @Test
    @DisplayName("28(버그 a). 시장가 매수 시 registerLimitOrder는 save/afterCommit 이전에 직접 호출되어, "
            + "이후 현재가 조회 실패로 트랜잭션이 롤백돼도 구독 등록 호출은 잔존하고 unregister는 발생하지 않는다")
    void marketOrder_kisFailureRollback_subscriptionRegistrationLeaksAndNeverUnregistered() {
        // given: 현재가 캐시 없음(신규 종목 코드) + KIS 현재가 조회 실패로 Mono.error
        Account account = createAccount(new BigDecimal("1000000"));
        Stock stock = createStock();
        var request = new MarketOrderCreateRequest(account.getAccountId(), stock.getCode(), 10, OrderMethod.BUY);

        org.mockito.Mockito.when(stockDetailService.getCurrentPrice(anyString()))
                .thenReturn(Mono.error(new RuntimeException("KIS API 장애(시뮬레이션)")));

        // when / then: calculateMarketHoldAmount 내부에서 현재가 조회 실패 → BadRequestException → 트랜잭션 롤백
        org.junit.jupiter.api.Assertions.assertThrows(BadRequestException.class,
                () -> orderService.createMarketOrder(request));

        // then(버그 a 동결): registerLimitOrder는 저장/afterCommit 이전에 직접 호출되므로 롤백과 무관하게 잔존
        verify(orderSubscriptionCoordinator, times(1)).registerLimitOrder(stock.getCode());
        // unregister는 어디에서도 호출되지 않음(보상 로직 없음)
        verify(orderSubscriptionCoordinator, never()).unregisterLimitOrder(stock.getCode());

        // then: 주문/홀딩 금액은 롤백되어 DB에 남지 않음
        List<Order> orders = orderRepository.findAllPendingOrdersByAccountId(
                account.getAccountId(),
                List.of(grit.stockIt.domain.order.entity.OrderStatus.PENDING)
        );
        assertThat(orders).isEmpty();
        Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloaded.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        // 오더북에도 당연히 반영되지 않음(afterCommit 자체가 발화되지 않음)
        verify(redisOrderBookRepository, never()).addOrder(any(Order.class));
    }
}
