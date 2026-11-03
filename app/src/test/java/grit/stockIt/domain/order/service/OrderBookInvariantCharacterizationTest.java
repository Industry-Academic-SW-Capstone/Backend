package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.queue.MatchingCommandType;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
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
import grit.stockIt.global.support.OutboxCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

// 컨테이너·프로파일 설정은 IntegrationTestSupport 싱글턴을 상속.
// 오더북(afterCommit) 불변식은 실 커밋에 의존하므로 테스트 메서드에 @Transactional을 붙이지 않는다
// (스프링 @Transactional 롤백 테스트는 afterCommit 콜백을 발화시키지 않아 계획에서 금지됨).
// 격리 경화: 이 클래스는 OrderCancelQueryCharacterizationTest와 동일한
// @SpyBean(orderBookRepository) + @MockBean(StockDetailService) 구성이라
// 스프링이 캐시된 ApplicationContext(=동일 spy 싱글턴)를 재사용한다. @BeforeEach의 Mockito.reset()만으로는
// 형제 클래스 간 invocation 누출을 완전히 배제할 수 없으므로, 클래스 종료 시 컨텍스트를 폐기해
// 다음 클래스가 항상 새 spy 인스턴스를 받도록 구조적으로 격리한다(느리지만 결정적).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("OrderService 오더북 반영 불변식 특성화 테스트")
class OrderBookInvariantCharacterizationTest extends IntegrationTestSupport {

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
    private OrderBookRepository orderBookRepository;

    @MockBean
    private StockDetailService stockDetailService;

    private Member testMember;
    private Contest testContest;

    @BeforeEach
    void setUp() {
        // @SpyBean은 캐시된 컨텍스트에서 테스트 간 공유되므로, 각 테스트 시작 시 stub·호출기록을 초기화해
        // 이전 테스트의 addOrder 호출이 누출되지 않게 한다(격리).
        org.mockito.Mockito.reset(orderBookRepository);
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
    @DisplayName("27a. 지정가 주문이 커밋되면 거래소로 보낼 주문 명령도 함께 커밋된다")
    void afterCommit_normalCommit_addsOrderToOrderBook() {
        // given: 매수 주문에 필요한 충분한 현금을 가진 계좌
        Account account = createAccount(new BigDecimal("1000000"));
        Stock stock = createStock();
        var request = new LimitOrderCreateRequest(account.getAccountId(), stock.getCode(),
                new BigDecimal("100"), 10, OrderMethod.BUY);

        // when
        var response = orderService.createLimitOrder(request);

        // then: 주문과 주문 명령이 같은 트랜잭션이라, 커밋된 주문은 반드시 거래소 오더북으로 간다
        assertThat(orderCommandWritten(response.orderId(), stock.getCode())).isTrue();
    }

    // ===== 27b: 강제(예외 기반) 롤백 → 오더북 미반영 =====
    @Test
    @DisplayName("27b. 현금 부족으로 롤백되면 주문도 주문 명령도 남지 않는다")
    void afterCommit_exceptionBasedRollback_neverAddsOrderToOrderBook() {
        // given: 홀딩 가능 금액보다 부족한 현금 계좌 (BadRequestException 유발용, @Transactional 롤백 테스트 아님)
        Account account = createAccount(new BigDecimal("1"));
        Stock stock = createStock();
        var request = new LimitOrderCreateRequest(account.getAccountId(), stock.getCode(),
                new BigDecimal("100"), 10, OrderMethod.BUY);

        // when / then: ensureSufficientCash에서 BadRequestException → 트랜잭션 전체 롤백
        org.junit.jupiter.api.Assertions.assertThrows(BadRequestException.class,
                () -> orderService.createLimitOrder(request));

        // then: 주문 자체가 저장되지 않았으므로 오더북에 나타날 수 없다
        List<Order> orders = orderRepository.findAllPendingOrdersByAccountId(
                account.getAccountId(),
                List.of(grit.stockIt.domain.order.entity.OrderStatus.PENDING)
        );
        assertThat(orders).isEmpty();
        assertThat(OutboxCommands.take(jdbcTemplate, stock.getCode())).isEmpty();
    }

    // ===== 28: 시장가 KIS 실패 롤백 =====
    // 예전에는 저장 전에 구독을 먼저 등록해 롤백돼도 구독이 남았다(버그 a). 이제 구독은 시세가 거래소 통보로만 연다.
    @Test
    @DisplayName("28. 시장가 매수 중 상한가 조회가 실패하면 주문과 홀딩 금액이 롤백된다")
    void marketOrder_kisFailureRollback_orderAndHoldRolledBack() {
        // given: 상한가 조회 실패로 Mono.error
        Account account = createAccount(new BigDecimal("1000000"));
        Stock stock = createStock();
        var request = new MarketOrderCreateRequest(account.getAccountId(), stock.getCode(), 10, OrderMethod.BUY);

        org.mockito.Mockito.when(stockDetailService.getUpperLimitPrice(anyString()))
                .thenReturn(Mono.error(new RuntimeException("KIS API 장애(시뮬레이션)")));

        // when / then: calculateMarketHoldAmount 내부에서 상한가 조회 실패 → BadRequestException → 트랜잭션 롤백
        org.junit.jupiter.api.Assertions.assertThrows(BadRequestException.class,
                () -> orderService.createMarketOrder(request));

        // then: 주문/홀딩 금액은 롤백되어 DB에 남지 않음
        List<Order> orders = orderRepository.findAllPendingOrdersByAccountId(
                account.getAccountId(),
                List.of(grit.stockIt.domain.order.entity.OrderStatus.PENDING)
        );
        assertThat(orders).isEmpty();
        Account reloaded = accountRepository.findById(account.getAccountId()).orElseThrow();
        assertThat(reloaded.getHoldAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private boolean orderCommandWritten(Long orderId, String stockCode) {
        return OutboxCommands.take(jdbcTemplate, stockCode).stream()
                .anyMatch(command -> command.type() == MatchingCommandType.ORDER && orderId.equals(command.orderId()));
    }
}
