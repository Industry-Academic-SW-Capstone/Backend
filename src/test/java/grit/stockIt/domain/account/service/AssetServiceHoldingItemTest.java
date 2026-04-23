package grit.stockIt.domain.account.service;

import grit.stockIt.domain.account.dto.AssetResponse;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.service.StockDetailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AssetService - HoldingItem 손익 필드 테스트")
class AssetServiceHoldingItemTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountStockRepository accountStockRepository;
    @Mock private StockDetailService stockDetailService;

    @InjectMocks private AssetService assetService;

    private Member member;
    private Contest contest;
    private Account account;

    @BeforeEach
    void setUp() {
        member = Member.builder()
                .memberId(1L)
                .name("테스터")
                .email("test@test.com")
                .provider(AuthProvider.LOCAL)
                .build();

        contest = Contest.builder()
                .contestId(1L)
                .contestName("테스트 대회")
                .seedMoney(10_000_000L)
                .commissionRate(BigDecimal.ZERO)
                .startDate(java.time.LocalDateTime.now())
                .build();

        account = Account.builder()
                .accountId(1L)
                .member(member)
                .contest(contest)
                .accountName("테스트 계좌")
                .cash(new BigDecimal("5000000"))
                .isDefault(true)
                .build();

        // SecurityContext 설정
        var auth = new UsernamePasswordAuthenticationToken("test@test.com", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Stock createStock(String code, String name) {
        return Stock.builder()
                .code(code)
                .name(name)
                .marketType("KOSPI")
                .build();
    }

    private AccountStock createAccountStock(Account account, Stock stock, int quantity, BigDecimal avgPrice) {
        return AccountStock.create(account, stock, quantity, avgPrice);
    }

    // ==================== 테스트 1: 기본 손익 계산 (이익) ====================

    @Test
    @DisplayName("1. 주가 상승 시 양수 손익 및 수익률 계산")
    void test_profitWhenPriceUp() {
        // given: 70,000원에 10주 매수 → 현재가 72,000원
        Stock stock = createStock("005930", "삼성전자");
        AccountStock as = createAccountStock(account, stock, 10, new BigDecimal("70000"));

        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountStockRepository.findByAccountIdWithStock(1L)).thenReturn(List.of(as));
        when(stockDetailService.getCurrentPrice(anyString())).thenReturn(Mono.just(new BigDecimal("72000")));

        // when
        AssetResponse response = assetService.getAssets(1L);

        // then
        assertThat(response.holdings()).hasSize(1);
        AssetResponse.HoldingItem item = response.holdings().get(0);

        assertThat(item.averagePrice()).isEqualByComparingTo("70000");
        assertThat(item.currentPrice()).isEqualByComparingTo("72000");
        assertThat(item.quantity()).isEqualTo(10);
        assertThat(item.totalValue()).isEqualByComparingTo("720000");                 // 72000 × 10
        assertThat(item.investmentPrincipal()).isEqualByComparingTo("700000");        // 70000 × 10
        assertThat(item.profitLossPerShare()).isEqualByComparingTo("2000");            // 72000 - 70000
        assertThat(item.profitLossTotal()).isEqualByComparingTo("20000");              // 2000 × 10
        assertThat(item.profitRate()).isEqualByComparingTo("2.86");                    // (20000/700000)*100
    }

    // ==================== 테스트 2: 손실 계산 ====================

    @Test
    @DisplayName("2. 주가 하락 시 음수 손익 및 수익률 계산")
    void test_lossWhenPriceDown() {
        // given: 50,000원에 20주 매수 → 현재가 45,000원
        Stock stock = createStock("000660", "SK하이닉스");
        AccountStock as = createAccountStock(account, stock, 20, new BigDecimal("50000"));

        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountStockRepository.findByAccountIdWithStock(1L)).thenReturn(List.of(as));
        when(stockDetailService.getCurrentPrice(anyString())).thenReturn(Mono.just(new BigDecimal("45000")));

        // when
        AssetResponse response = assetService.getAssets(1L);

        // then
        AssetResponse.HoldingItem item = response.holdings().get(0);

        assertThat(item.profitLossPerShare()).isEqualByComparingTo("-5000");           // 45000 - 50000
        assertThat(item.profitLossTotal()).isEqualByComparingTo("-100000");            // -5000 × 20
        assertThat(item.profitRate()).isEqualByComparingTo("-10.00");                  // (-100000/1000000)*100
        assertThat(item.investmentPrincipal()).isEqualByComparingTo("1000000");        // 50000 × 20
    }

    // ==================== 테스트 3: 손익 0 (가격 변동 없음) ====================

    @Test
    @DisplayName("3. 가격 변동 없으면 손익 0, 수익률 0")
    void test_noProfitOrLoss() {
        // given: 100,000원에 5주 매수 → 현재가 100,000원
        Stock stock = createStock("035720", "카카오");
        AccountStock as = createAccountStock(account, stock, 5, new BigDecimal("100000"));

        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountStockRepository.findByAccountIdWithStock(1L)).thenReturn(List.of(as));
        when(stockDetailService.getCurrentPrice(anyString())).thenReturn(Mono.just(new BigDecimal("100000")));

        // when
        AssetResponse response = assetService.getAssets(1L);

        // then
        AssetResponse.HoldingItem item = response.holdings().get(0);

        assertThat(item.profitLossPerShare()).isEqualByComparingTo("0");
        assertThat(item.profitLossTotal()).isEqualByComparingTo("0");
        assertThat(item.profitRate()).isEqualByComparingTo("0.00");
    }

    // ==================== 테스트 4: 여러 종목 동시 보유 ====================

    @Test
    @DisplayName("4. 여러 종목 보유 시 각각의 손익 정확히 계산")
    void test_multipleHoldings() {
        // given
        Stock stock1 = createStock("005930", "삼성전자");
        Stock stock2 = createStock("000660", "SK하이닉스");
        AccountStock as1 = createAccountStock(account, stock1, 10, new BigDecimal("70000"));  // +2,000
        AccountStock as2 = createAccountStock(account, stock2, 5, new BigDecimal("120000"));  // -10,000

        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountStockRepository.findByAccountIdWithStock(1L)).thenReturn(List.of(as1, as2));
        when(stockDetailService.getCurrentPrice("005930")).thenReturn(Mono.just(new BigDecimal("72000")));
        when(stockDetailService.getCurrentPrice("000660")).thenReturn(Mono.just(new BigDecimal("110000")));

        // when
        AssetResponse response = assetService.getAssets(1L);

        // then
        assertThat(response.holdings()).hasSize(2);

        // 삼성전자: +2,000원/주
        AssetResponse.HoldingItem samsung = response.holdings().stream()
                .filter(h -> h.stockCode().equals("005930")).findFirst().orElseThrow();
        assertThat(samsung.profitLossPerShare()).isEqualByComparingTo("2000");
        assertThat(samsung.profitLossTotal()).isEqualByComparingTo("20000");

        // SK하이닉스: -10,000원/주
        AssetResponse.HoldingItem hynix = response.holdings().stream()
                .filter(h -> h.stockCode().equals("000660")).findFirst().orElseThrow();
        assertThat(hynix.profitLossPerShare()).isEqualByComparingTo("-10000");
        assertThat(hynix.profitLossTotal()).isEqualByComparingTo("-50000");

        // 총 자산: 현금 5,000,000 + 삼성 720,000 + SK 550,000 = 6,270,000
        assertThat(response.totalAssets()).isEqualByComparingTo("6270000");
        assertThat(response.stockValue()).isEqualByComparingTo("1270000");
    }

    // ==================== 테스트 5: 보유 종목 없음 ====================

    @Test
    @DisplayName("5. 보유 종목 없을 때 빈 holdings, 총자산 = 현금")
    void test_noHoldings() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountStockRepository.findByAccountIdWithStock(1L)).thenReturn(List.of());

        // when
        AssetResponse response = assetService.getAssets(1L);

        // then
        assertThat(response.holdings()).isEmpty();
        assertThat(response.totalAssets()).isEqualByComparingTo("5000000");
        assertThat(response.cash()).isEqualByComparingTo("5000000");
        assertThat(response.stockValue()).isEqualByComparingTo("0");
    }

    // ==================== 테스트 6: 소수점 정밀도 테스트 ====================

    @Test
    @DisplayName("6. 소수점 수익률 정밀도 확인 (반올림)")
    void test_precisionRounding() {
        // given: 33,333원에 3주 매수 → 현재가 34,000원
        // profitLossPerShare = 667, total = 2001
        // investmentPrincipal = 99999
        // profitRate = (2001 / 99999) * 100 = 2.0010... → 2.00
        Stock stock = createStock("TEST01", "테스트종목");
        AccountStock as = createAccountStock(account, stock, 3, new BigDecimal("33333"));

        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(accountStockRepository.findByAccountIdWithStock(1L)).thenReturn(List.of(as));
        when(stockDetailService.getCurrentPrice(anyString())).thenReturn(Mono.just(new BigDecimal("34000")));

        // when
        AssetResponse response = assetService.getAssets(1L);

        // then
        AssetResponse.HoldingItem item = response.holdings().get(0);
        assertThat(item.profitLossPerShare()).isEqualByComparingTo("667");
        assertThat(item.profitLossTotal()).isEqualByComparingTo("2001");
        // (2001 / 99999) * 100 = 2.001... → scale 4 → 2.0010 → *100 → 200.10 → ...
        // Actually: 2001/99999 = 0.020010... → HALF_UP at scale 4 = 0.0200 → *100 = 2.00
        assertThat(item.profitRate()).isEqualByComparingTo("2.00");
    }
}
