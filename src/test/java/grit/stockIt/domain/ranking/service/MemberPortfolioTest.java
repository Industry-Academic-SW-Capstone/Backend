package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.mission.service.MissionService;
import grit.stockIt.domain.ranking.dto.MemberPortfolioResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RankingService - 대회 참가자 포트폴리오 조회 테스트")
class MemberPortfolioTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountStockRepository accountStockRepository;
    @Mock private ContestRepository contestRepository;
    @Mock private MissionService missionService;
    @Mock private RedisMarketDataRepository redisMarketDataRepository;
    @Mock private StockDetailService stockDetailService;
    @Mock private Environment environment;

    @InjectMocks private RankingService rankingService;

    private Contest contest;
    private Member requester;
    private Member target;
    private Account requesterAccount;
    private Account targetAccount;

    @BeforeEach
    void setUp() {
        contest = Contest.builder()
                .contestId(1L)
                .contestName("2026 모의투자 대회")
                .seedMoney(10_000_000L)
                .commissionRate(BigDecimal.ZERO)
                .startDate(LocalDateTime.now().minusDays(7))
                .endDate(LocalDateTime.now().plusDays(23))
                .build();

        requester = Member.builder()
                .memberId(1L)
                .name("요청자")
                .email("requester@test.com")
                .provider(AuthProvider.LOCAL)
                .build();

        target = Member.builder()
                .memberId(2L)
                .name("대상자")
                .email("target@test.com")
                .provider(AuthProvider.LOCAL)
                .profileImage("https://example.com/profile.jpg")
                .build();

        requesterAccount = Account.builder()
                .accountId(10L)
                .member(requester)
                .contest(contest)
                .accountName("요청자 대회 계좌")
                .cash(new BigDecimal("5000000"))
                .isDefault(false)
                .build();

        targetAccount = Account.builder()
                .accountId(20L)
                .member(target)
                .contest(contest)
                .accountName("대상자 대회 계좌")
                .cash(new BigDecimal("3000000"))
                .isDefault(false)
                .build();

        // 공통 Mock 설정
        when(contestRepository.findById(1L)).thenReturn(Optional.of(contest));
        when(accountRepository.findByContest(contest)).thenReturn(List.of(requesterAccount, targetAccount));
        when(accountRepository.findByMemberIdAndContestId(2L, 1L)).thenReturn(Optional.of(targetAccount));

        // Redis 캐시 미스 → KIS API 호출하도록 설정
        when(redisMarketDataRepository.getLastPrice(anyString())).thenReturn(Optional.empty());
    }

    private Stock createStock(String code, String name) {
        return Stock.builder()
                .code(code)
                .name(name)
                .marketType("KOSPI")
                .build();
    }

    // ==================== 테스트 1: 정상 포트폴리오 조회 (종목 보유) ====================

    @Test
    @DisplayName("1. 같은 대회 참가자가 다른 참가자의 포트폴리오를 정상 조회")
    void test_viewOtherParticipantPortfolio() {
        // given: 대상자가 삼성전자 10주(평단가 70,000) 보유
        Stock samsung = createStock("005930", "삼성전자");
        AccountStock as = AccountStock.create(targetAccount, samsung, 10, new BigDecimal("70000"));

        when(accountStockRepository.findByAccountIdWithStock(20L)).thenReturn(List.of(as));
        when(stockDetailService.getCurrentPrice("005930")).thenReturn(Mono.just(new BigDecimal("75000")));

        // when
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 2L, "requester@test.com");

        // then
        assertThat(response.memberId()).isEqualTo(2L);
        assertThat(response.nickname()).isEqualTo("대상자");
        assertThat(response.profileImage()).isEqualTo("https://example.com/profile.jpg");
        assertThat(response.cash()).isEqualByComparingTo("3000000");
        assertThat(response.holdings()).hasSize(1);

        // 총자산: 현금 3,000,000 + 삼성 750,000 = 3,750,000
        assertThat(response.totalAssets()).isEqualByComparingTo("3750000");
        assertThat(response.stockValue()).isEqualByComparingTo("750000");

        // 현금 비중: 3,000,000 / 3,750,000 * 100 = 80.00%
        assertThat(response.cashPercent()).isEqualByComparingTo("80.00");

        // 수익률: (3,750,000 - 10,000,000) / 10,000,000 * 100 = -62.50%
        assertThat(response.returnRate()).isEqualByComparingTo("-62.50");

        // 종목 상세
        MemberPortfolioResponse.PortfolioItem item = response.holdings().get(0);
        assertThat(item.stockCode()).isEqualTo("005930");
        assertThat(item.stockName()).isEqualTo("삼성전자");
        assertThat(item.quantity()).isEqualTo(10);
        assertThat(item.averagePrice()).isEqualByComparingTo("70000");
        assertThat(item.currentPrice()).isEqualByComparingTo("75000");
        assertThat(item.profitLossPerShare()).isEqualByComparingTo("5000");
        assertThat(item.profitLossTotal()).isEqualByComparingTo("50000");
        assertThat(item.profitRate()).isEqualByComparingTo("7.14");  // 50000/700000*100

        // 종목 비중: 750,000 / 3,750,000 * 100 = 20.00%
        assertThat(item.percent()).isEqualByComparingTo("20.00");
    }

    // ==================== 테스트 2: 다른 대회 참가자가 조회 시 403 ====================

    @Test
    @DisplayName("2. 같은 대회에 참가하지 않은 사용자가 조회하면 ForbiddenException")
    void test_forbiddenWhenNotInSameContest() {
        // given: outsider@test.com은 대회에 참가하지 않음
        // accountRepository.findByContest는 requester, target만 반환

        // when & then
        assertThatThrownBy(() ->
                rankingService.getMemberPortfolio(1L, 2L, "outsider@test.com")
        ).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("같은 대회 참가자만");
    }

    // ==================== 테스트 3: 존재하지 않는 대회 조회 ====================

    @Test
    @DisplayName("3. 존재하지 않는 대회 ID로 조회 시 예외")
    void test_contestNotFound() {
        when(contestRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                rankingService.getMemberPortfolio(999L, 2L, "requester@test.com")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대회를 찾을 수 없습니다");
    }

    // ==================== 테스트 4: 대상 회원이 대회에 없는 경우 ====================

    @Test
    @DisplayName("4. 대상 회원이 해당 대회에 참가하지 않은 경우 예외")
    void test_targetMemberNotInContest() {
        when(accountRepository.findByMemberIdAndContestId(999L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                rankingService.getMemberPortfolio(1L, 999L, "requester@test.com")
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("대회 계좌를 찾을 수 없습니다");
    }

    // ==================== 테스트 5: 보유 종목 없이 현금만 있는 참가자 ====================

    @Test
    @DisplayName("5. 보유 종목 없이 현금만 보유한 참가자 포트폴리오 조회")
    void test_cashOnlyPortfolio() {
        // given: 대상자가 종목 없이 현금 3,000,000만 보유
        when(accountStockRepository.findByAccountIdWithStock(20L)).thenReturn(List.of());

        // when
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 2L, "requester@test.com");

        // then
        assertThat(response.holdings()).isEmpty();
        assertThat(response.totalAssets()).isEqualByComparingTo("3000000");
        assertThat(response.cash()).isEqualByComparingTo("3000000");
        assertThat(response.cashPercent()).isEqualByComparingTo("100.00");
        assertThat(response.stockValue()).isEqualByComparingTo("0");
        assertThat(response.returnRate()).isEqualByComparingTo("-70.00");  // (3M - 10M) / 10M * 100
    }

    // ==================== 테스트 6: 여러 종목 보유 시 비중 합계 검증 ====================

    @Test
    @DisplayName("6. 여러 종목 보유 시 현금 + 종목 비중 합계가 약 100%")
    void test_multipleHoldingsPercentSum() {
        // given
        Stock stock1 = createStock("005930", "삼성전자");
        Stock stock2 = createStock("000660", "SK하이닉스");
        Stock stock3 = createStock("035720", "카카오");

        AccountStock as1 = AccountStock.create(targetAccount, stock1, 10, new BigDecimal("70000"));
        AccountStock as2 = AccountStock.create(targetAccount, stock2, 5, new BigDecimal("150000"));
        AccountStock as3 = AccountStock.create(targetAccount, stock3, 20, new BigDecimal("50000"));

        when(accountStockRepository.findByAccountIdWithStock(20L)).thenReturn(List.of(as1, as2, as3));
        when(stockDetailService.getCurrentPrice("005930")).thenReturn(Mono.just(new BigDecimal("72000")));
        when(stockDetailService.getCurrentPrice("000660")).thenReturn(Mono.just(new BigDecimal("155000")));
        when(stockDetailService.getCurrentPrice("035720")).thenReturn(Mono.just(new BigDecimal("48000")));

        // when
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 2L, "requester@test.com");

        // then
        assertThat(response.holdings()).hasSize(3);

        // 비중 합계 검증 (현금 비중 + 종목 비중 합 ≈ 100%)
        BigDecimal totalPercent = response.cashPercent();
        for (MemberPortfolioResponse.PortfolioItem item : response.holdings()) {
            totalPercent = totalPercent.add(item.percent());
        }
        // 반올림으로 인해 99.98~100.02 범위
        assertThat(totalPercent.doubleValue()).isBetween(99.90, 100.10);

        // 총자산: 현금 3,000,000 + 삼성 720,000 + SK 775,000 + 카카오 960,000
        BigDecimal expectedTotal = new BigDecimal("3000000")
                .add(new BigDecimal("720000"))
                .add(new BigDecimal("775000"))
                .add(new BigDecimal("960000"));
        assertThat(response.totalAssets()).isEqualByComparingTo(expectedTotal);
    }

    // ==================== 테스트 7: 평가액 내림차순 정렬 검증 ====================

    @Test
    @DisplayName("7. 종목이 평가액 내림차순으로 정렬되는지 검증")
    void test_holdingsSortedByValueDesc() {
        // given: 3종목 평가액이 다른 경우
        Stock stock1 = createStock("005930", "삼성전자");    // 10 × 72000 = 720,000 (2위)
        Stock stock2 = createStock("000660", "SK하이닉스");   // 5 × 155000 = 775,000 (1위)
        Stock stock3 = createStock("035720", "카카오");       // 3 × 48000  = 144,000 (3위)

        AccountStock as1 = AccountStock.create(targetAccount, stock1, 10, new BigDecimal("70000"));
        AccountStock as2 = AccountStock.create(targetAccount, stock2, 5, new BigDecimal("150000"));
        AccountStock as3 = AccountStock.create(targetAccount, stock3, 3, new BigDecimal("50000"));

        when(accountStockRepository.findByAccountIdWithStock(20L)).thenReturn(List.of(as1, as2, as3));
        when(stockDetailService.getCurrentPrice("005930")).thenReturn(Mono.just(new BigDecimal("72000")));
        when(stockDetailService.getCurrentPrice("000660")).thenReturn(Mono.just(new BigDecimal("155000")));
        when(stockDetailService.getCurrentPrice("035720")).thenReturn(Mono.just(new BigDecimal("48000")));

        // when
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 2L, "requester@test.com");

        // then: SK하이닉스(775,000) > 삼성전자(720,000) > 카카오(144,000)
        List<MemberPortfolioResponse.PortfolioItem> items = response.holdings();
        assertThat(items.get(0).stockName()).isEqualTo("SK하이닉스");
        assertThat(items.get(1).stockName()).isEqualTo("삼성전자");
        assertThat(items.get(2).stockName()).isEqualTo("카카오");
    }

    // ==================== 테스트 8: 자기 자신 포트폴리오 조회 ====================

    @Test
    @DisplayName("8. 자기 자신의 포트폴리오도 이 API로 조회 가능")
    void test_viewOwnPortfolio() {
        // given: 요청자 = 대상자 (memberId = 1)
        when(accountRepository.findByMemberIdAndContestId(1L, 1L)).thenReturn(Optional.of(requesterAccount));

        Stock stock = createStock("005930", "삼성전자");
        AccountStock as = AccountStock.create(requesterAccount, stock, 5, new BigDecimal("80000"));

        when(accountStockRepository.findByAccountIdWithStock(10L)).thenReturn(List.of(as));
        when(stockDetailService.getCurrentPrice("005930")).thenReturn(Mono.just(new BigDecimal("85000")));

        // when
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 1L, "requester@test.com");

        // then
        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("요청자");
        assertThat(response.holdings()).hasSize(1);
        assertThat(response.holdings().get(0).profitLossPerShare()).isEqualByComparingTo("5000");
    }
}
