package grit.stockIt.global.client;

import grit.stockIt.contract.BrokerQueryClient;
import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.contract.ExtraQueryClient;
import grit.stockIt.contract.TitleView;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.title.repository.MemberTitleRepository;
import grit.stockIt.domain.title.repository.TitleRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스를 나눠 띄우면 동기 조회가 HTTP 를 탄다. 실제 서버(내부 API 켬)에 HTTP 클라이언트로 물어, 같은 프로세스 구현과
// 같은 답이 오는지 본다 — 경로·본문 이름(SNAKE_CASE)·404 처리가 양쪽에서 어긋나면 여기서 드러난다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stockit.internal-api.enabled=true")
@DisplayName("서비스 사이 동기 조회 — HTTP 왕복 (통합 테스트)")
class InternalQueryApiRoundTripTest extends IntegrationTestSupport {

    private static final long GHOST = 999_999_999L;

    @LocalServerPort private int port;
    @Autowired private RestClient.Builder restClientBuilder;

    @Autowired private BrokerQueryClient localBroker;
    @Autowired private ExtraQueryClient localExtra;
    @Autowired private ExchangeQueryClient localExchange;

    @Autowired private MemberRepository memberRepository;
    @Autowired private ContestRepository contestRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountStockRepository accountStockRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private TitleRepository titleRepository;
    @Autowired private MemberTitleRepository memberTitleRepository;
    @Autowired private ExchangeOrderRepository exchangeOrderRepository;

    private BrokerQueryClient httpBroker;
    private ExtraQueryClient httpExtra;
    private ExchangeQueryClient httpExchange;

    @BeforeEach
    void setUp() {
        String baseUrl = "http://localhost:" + port;
        httpBroker = new HttpBrokerQueryClient(InternalRestClients.create(restClientBuilder, baseUrl));
        httpExtra = new HttpExtraQueryClient(InternalRestClients.create(restClientBuilder, baseUrl));
        httpExchange = new HttpExchangeQueryClient(InternalRestClients.create(restClientBuilder, baseUrl));
    }

    @Test
    @DisplayName("증권사: 기본 계좌 자산·주식 보유 회원·전체 회원이 같은 프로세스 구현과 같다")
    void brokerQueries() {
        Member member = memberWithStock();

        assertThat(httpBroker.defaultAccountAsset(member.getMemberId()).orElseThrow())
                .isEqualByComparingTo(localBroker.defaultAccountAsset(member.getMemberId()).orElseThrow());
        assertThat(httpBroker.defaultAccountAsset(GHOST)).isEmpty();
        assertThat(httpBroker.membersHoldingStock(List.of(member.getMemberId(), GHOST)))
                .containsExactly(member.getMemberId());
        assertThat(httpBroker.allMemberIds()).contains(member.getMemberId());
    }

    @Test
    @DisplayName("부가: 칭호·보유 여부·보유 칭호 목록이 같은 프로세스 구현과 같다")
    void extraQueries() {
        Member member = memberRepository.save(Member.builder()
                .name("왕복" + suffix()).email("roundtrip-" + suffix() + "@test.com").provider(AuthProvider.LOCAL).build());
        Title title = titleRepository.save(Title.builder()
                .name("왕복 칭호 " + UUID.randomUUID()).description("HTTP 왕복 테스트").build());
        Title other = titleRepository.save(Title.builder()
                .name("왕복 칭호 " + UUID.randomUUID()).description("보유하지 않음").build());
        memberTitleRepository.save(MemberTitle.builder().memberId(member.getMemberId()).title(title).build());

        assertThat(httpExtra.title(title.getId())).isEqualTo(localExtra.title(title.getId()));
        assertThat(httpExtra.title(GHOST)).isEmpty();
        assertThat(httpExtra.ownsTitle(member.getMemberId(), title.getId())).isTrue();
        assertThat(httpExtra.ownsTitle(member.getMemberId(), other.getId())).isFalse();
        assertThat(httpExtra.titlesOf(member.getMemberId()))
                .containsExactlyElementsOf(localExtra.titlesOf(member.getMemberId()))
                .extracting(TitleView::name).containsExactly(title.getName());
    }

    @Test
    @DisplayName("거래소: 열린 주문 목록이 같은 프로세스 구현과 같다")
    void exchangeQueries() {
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        exchangeOrderRepository.save(ExchangeOrder.accept(orderId, 1L, "005930", OrderMethod.BUY, OrderType.LIMIT,
                new BigDecimal("70000"), 3));

        assertThat(httpExchange.openOrders())
                .contains(new ExchangeQueryClient.OpenOrder(orderId, "005930"))
                .containsExactlyInAnyOrderElementsOf(localExchange.openOrders());
    }

    private Member memberWithStock() {
        String suffix = suffix();
        Member member = memberRepository.save(Member.builder()
                .name("왕복" + suffix).email("roundtrip-" + suffix + "@test.com").provider(AuthProvider.LOCAL).build());
        Contest contest = contestRepository.save(Contest.builder()
                .contestName("왕복 " + suffix)
                .startDate(LocalDateTime.now())
                .seedMoney(1_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .build());
        Account account = accountRepository.save(Account.builder()
                .member(member).contest(contest)
                .accountName("왕복 계좌 " + suffix)
                .cash(new BigDecimal("500000"))
                .holdAmount(BigDecimal.ZERO)
                .isDefault(true)
                .build());
        Stock stock = stockRepository.save(Stock.builder()
                .code("R" + suffix).name("왕복종목 " + suffix).marketType("KOSPI").build());
        accountStockRepository.save(AccountStock.create(account, stock, 3, new BigDecimal("10000")));
        return member;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
