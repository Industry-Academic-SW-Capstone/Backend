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

/**
 * 4명의 대회 참가자(A, B, C, D)가 서로의 포트폴리오를 조회하는 통합 시나리오 테스트
 *
 * - 사용자 A: 삼성전자 100만원 보유
 * - 사용자 B: SK하이닉스 100만원 보유
 * - 사용자 C: 삼성전자 30만원 + SK하이닉스 30만원 + 현금 40만원
 * - 사용자 D: 현금 100만원만 보유
 * - 외부인 E: 대회에 참가하지 않은 사용자
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("대회 참가자 간 포트폴리오 상호 조회 테스트")
class MemberPortfolioCrossViewTest {

    @Mock private AccountRepository accountRepository;
    @Mock private AccountStockRepository accountStockRepository;
    @Mock private ContestRepository contestRepository;
    @Mock private MissionService missionService;
    @Mock private RedisMarketDataRepository redisMarketDataRepository;
    @Mock private StockDetailService stockDetailService;
    @Mock private Environment environment;

    @InjectMocks private RankingService rankingService;

    private Contest contest;
    private Member memberA, memberB, memberC, memberD, memberE;
    private Account accountA, accountB, accountC, accountD;
    private Stock samsung, hynix;

    @BeforeEach
    void setUp() {
        // 대회 설정: 시드머니 100만원
        contest = Contest.builder()
                .contestId(1L)
                .contestName("test")
                .seedMoney(1_000_000L)
                .commissionRate(BigDecimal.ZERO)
                .startDate(LocalDateTime.now().minusDays(7))
                .endDate(LocalDateTime.now().plusDays(23))
                .build();

        // 종목 설정
        samsung = Stock.builder().code("005930").name("삼성전자").marketType("KOSPI").build();
        hynix = Stock.builder().code("000660").name("SK하이닉스").marketType("KOSPI").build();

        // 4명의 회원
        memberA = Member.builder().memberId(1L).name("사용자A").email("a@test.com").provider(AuthProvider.LOCAL).build();
        memberB = Member.builder().memberId(2L).name("사용자B").email("b@test.com").provider(AuthProvider.LOCAL).build();
        memberC = Member.builder().memberId(3L).name("사용자C").email("c@test.com").provider(AuthProvider.LOCAL).build();
        memberD = Member.builder().memberId(4L).name("사용자D").email("d@test.com").provider(AuthProvider.LOCAL).build();
        memberE = Member.builder().memberId(5L).name("외부인E").email("e@test.com").provider(AuthProvider.LOCAL).build();

        // A: 삼성전자 100만원 (현금 0)
        accountA = Account.builder().accountId(10L).member(memberA).contest(contest)
                .accountName("A계좌").cash(BigDecimal.ZERO).isDefault(false).build();
        // B: SK하이닉스 100만원 (현금 0)
        accountB = Account.builder().accountId(20L).member(memberB).contest(contest)
                .accountName("B계좌").cash(BigDecimal.ZERO).isDefault(false).build();
        // C: 삼성전자 30만원 + SK하이닉스 30만원 + 현금 40만원
        accountC = Account.builder().accountId(30L).member(memberC).contest(contest)
                .accountName("C계좌").cash(new BigDecimal("400000")).isDefault(false).build();
        // D: 현금 100만원만
        accountD = Account.builder().accountId(40L).member(memberD).contest(contest)
                .accountName("D계좌").cash(new BigDecimal("1000000")).isDefault(false).build();

        // 공통 Mock
        when(contestRepository.findById(1L)).thenReturn(Optional.of(contest));
        when(accountRepository.findByContest(contest)).thenReturn(List.of(accountA, accountB, accountC, accountD));

        when(accountRepository.findByMemberIdAndContestId(1L, 1L)).thenReturn(Optional.of(accountA));
        when(accountRepository.findByMemberIdAndContestId(2L, 1L)).thenReturn(Optional.of(accountB));
        when(accountRepository.findByMemberIdAndContestId(3L, 1L)).thenReturn(Optional.of(accountC));
        when(accountRepository.findByMemberIdAndContestId(4L, 1L)).thenReturn(Optional.of(accountD));

        // 보유 종목 설정
        // A: 삼성전자 10주 × 100,000원 = 1,000,000원
        AccountStock asA = AccountStock.create(accountA, samsung, 10, new BigDecimal("100000"));
        when(accountStockRepository.findByAccountIdWithStock(10L)).thenReturn(List.of(asA));

        // B: SK하이닉스 5주 × 200,000원 = 1,000,000원
        AccountStock asB = AccountStock.create(accountB, hynix, 5, new BigDecimal("200000"));
        when(accountStockRepository.findByAccountIdWithStock(20L)).thenReturn(List.of(asB));

        // C: 삼성전자 3주 × 100,000원 = 300,000원 + SK하이닉스 1.5주(→2주) × 150,000원 = 300,000원
        AccountStock asC1 = AccountStock.create(accountC, samsung, 3, new BigDecimal("100000"));
        AccountStock asC2 = AccountStock.create(accountC, hynix, 2, new BigDecimal("150000"));
        when(accountStockRepository.findByAccountIdWithStock(30L)).thenReturn(List.of(asC1, asC2));

        // D: 종목 없음
        when(accountStockRepository.findByAccountIdWithStock(40L)).thenReturn(List.of());

        // 현재가 (매입가와 동일하게 설정하여 손익 검증 단순화)
        when(redisMarketDataRepository.getLastPrice(anyString())).thenReturn(Optional.empty());
        when(stockDetailService.getCurrentPrice("005930")).thenReturn(Mono.just(new BigDecimal("100000")));
        when(stockDetailService.getCurrentPrice("000660")).thenReturn(Mono.just(new BigDecimal("200000")));
    }

    // ==================== 테스트 1: A가 B의 포트폴리오 조회 ====================

    @Test
    @DisplayName("1. 사용자A → 사용자B 포트폴리오 조회 (SK하이닉스 100%)")
    void test_A_views_B() {
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 2L, "a@test.com");

        assertThat(response.memberId()).isEqualTo(2L);
        assertThat(response.nickname()).isEqualTo("사용자B");
        assertThat(response.cash()).isEqualByComparingTo("0");
        assertThat(response.cashPercent()).isEqualByComparingTo("0.00");
        assertThat(response.totalAssets()).isEqualByComparingTo("1000000");
        assertThat(response.holdings()).hasSize(1);

        MemberPortfolioResponse.PortfolioItem item = response.holdings().get(0);
        assertThat(item.stockName()).isEqualTo("SK하이닉스");
        assertThat(item.percent()).isEqualByComparingTo("100.00");
        assertThat(item.quantity()).isEqualTo(5);
        assertThat(item.averagePrice()).isEqualByComparingTo("200000");
    }

    // ==================== 테스트 2: B가 A의 포트폴리오 조회 ====================

    @Test
    @DisplayName("2. 사용자B → 사용자A 포트폴리오 조회 (삼성전자 100%)")
    void test_B_views_A() {
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 1L, "b@test.com");

        assertThat(response.memberId()).isEqualTo(1L);
        assertThat(response.nickname()).isEqualTo("사용자A");
        assertThat(response.cash()).isEqualByComparingTo("0");
        assertThat(response.cashPercent()).isEqualByComparingTo("0.00");
        assertThat(response.totalAssets()).isEqualByComparingTo("1000000");
        assertThat(response.holdings()).hasSize(1);

        MemberPortfolioResponse.PortfolioItem item = response.holdings().get(0);
        assertThat(item.stockName()).isEqualTo("삼성전자");
        assertThat(item.percent()).isEqualByComparingTo("100.00");
        assertThat(item.quantity()).isEqualTo(10);
        assertThat(item.averagePrice()).isEqualByComparingTo("100000");
    }

    // ==================== 테스트 3: C가 D의 포트폴리오 조회 (현금 100%) ====================

    @Test
    @DisplayName("3. 사용자C → 사용자D 포트폴리오 조회 (현금만 100%)")
    void test_C_views_D() {
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 4L, "c@test.com");

        assertThat(response.memberId()).isEqualTo(4L);
        assertThat(response.nickname()).isEqualTo("사용자D");
        assertThat(response.cash()).isEqualByComparingTo("1000000");
        assertThat(response.cashPercent()).isEqualByComparingTo("100.00");
        assertThat(response.stockValue()).isEqualByComparingTo("0");
        assertThat(response.holdings()).isEmpty();
        assertThat(response.returnRate()).isEqualByComparingTo("0.00");
    }

    // ==================== 테스트 4: D가 C의 포트폴리오 조회 (혼합 포트폴리오) ====================

    @Test
    @DisplayName("4. 사용자D → 사용자C 포트폴리오 조회 (삼성 30% + 하이닉스 40% + 현금 40%)")
    void test_D_views_C() {
        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 3L, "d@test.com");

        assertThat(response.memberId()).isEqualTo(3L);
        assertThat(response.nickname()).isEqualTo("사용자C");

        // 총자산: 현금 400,000 + 삼성 300,000 + SK 400,000 = 1,100,000
        assertThat(response.totalAssets()).isEqualByComparingTo("1100000");
        assertThat(response.cash()).isEqualByComparingTo("400000");
        assertThat(response.stockValue()).isEqualByComparingTo("700000");

        // 현금 비중: 400,000 / 1,100,000 * 100 = 36.36%
        assertThat(response.cashPercent()).isEqualByComparingTo("36.36");

        // 수익률: (1,100,000 - 1,000,000) / 1,000,000 * 100 = 10.00%
        assertThat(response.returnRate()).isEqualByComparingTo("10.00");

        assertThat(response.holdings()).hasSize(2);

        // 평가액 내림차순 정렬: SK하이닉스(400,000) > 삼성전자(300,000)
        MemberPortfolioResponse.PortfolioItem first = response.holdings().get(0);
        assertThat(first.stockName()).isEqualTo("SK하이닉스");
        assertThat(first.totalValue()).isEqualByComparingTo("400000");
        // SK하이닉스 비중: 400,000 / 1,100,000 * 100 = 36.36%
        assertThat(first.percent()).isEqualByComparingTo("36.36");

        MemberPortfolioResponse.PortfolioItem second = response.holdings().get(1);
        assertThat(second.stockName()).isEqualTo("삼성전자");
        assertThat(second.totalValue()).isEqualByComparingTo("300000");
        // 삼성전자 비중: 300,000 / 1,100,000 * 100 = 27.27%
        assertThat(second.percent()).isEqualByComparingTo("27.27");

        // 비중 합계 확인: 36.36 + 36.36 + 27.27 ≈ 99.99~100.01
        BigDecimal totalPct = response.cashPercent()
                .add(first.percent())
                .add(second.percent());
        assertThat(totalPct.doubleValue()).isBetween(99.90, 100.10);
    }

    // ==================== 테스트 5: A가 C의 포트폴리오 조회 (다른 조합) ====================

    @Test
    @DisplayName("5. 사용자A → 사용자C 포트폴리오 조회 (혼합 포트폴리오, 손익 포함)")
    void test_A_views_C_withProfitLoss() {
        // 현재가를 변경하여 손익 발생시킴
        // 삼성전자: 매입가 100,000 → 현재가 110,000 (+10%)
        // SK하이닉스: 매입가 150,000 → 현재가 200,000 (+33.33%)
        // (기본 setUp에서 현재가: 삼성 100,000, SK 200,000)
        // C의 삼성: 3주 × 100,000 = 300,000 (매입 300,000, 손익 0)
        // C의 SK: 2주 × 200,000 = 400,000 (매입 300,000, 이익 100,000)

        MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, 3L, "a@test.com");

        assertThat(response.memberId()).isEqualTo(3L);

        // SK하이닉스 손익 확인
        MemberPortfolioResponse.PortfolioItem skItem = response.holdings().stream()
                .filter(h -> h.stockCode().equals("000660")).findFirst().orElseThrow();
        assertThat(skItem.averagePrice()).isEqualByComparingTo("150000");
        assertThat(skItem.currentPrice()).isEqualByComparingTo("200000");
        assertThat(skItem.profitLossPerShare()).isEqualByComparingTo("50000");
        assertThat(skItem.profitLossTotal()).isEqualByComparingTo("100000");  // 50,000 × 2
        assertThat(skItem.profitRate()).isEqualByComparingTo("33.33");        // 100,000/300,000*100

        // 삼성전자 손익 확인 (매입가 = 현재가 → 손익 0)
        MemberPortfolioResponse.PortfolioItem samsungItem = response.holdings().stream()
                .filter(h -> h.stockCode().equals("005930")).findFirst().orElseThrow();
        assertThat(samsungItem.profitLossPerShare()).isEqualByComparingTo("0");
        assertThat(samsungItem.profitLossTotal()).isEqualByComparingTo("0");
        assertThat(samsungItem.profitRate()).isEqualByComparingTo("0.00");
    }

    // ==================== 테스트 6: 외부인 E가 대회 참가자 조회 시 403 ====================

    @Test
    @DisplayName("6. 대회 미참가자(외부인E)가 참가자A의 포트폴리오 조회 시 ForbiddenException")
    void test_outsider_E_blocked() {
        assertThatThrownBy(() ->
                rankingService.getMemberPortfolio(1L, 1L, "e@test.com")
        ).isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("같은 대회 참가자만");
    }

    // ==================== 테스트 7: 모든 참가자가 서로 전부 조회 가능 검증 ====================

    @Test
    @DisplayName("7. 4명 참가자 모두가 서로의 포트폴리오를 조회 가능 (12가지 조합)")
    void test_allParticipantsCanViewEachOther() {
        String[] emails = {"a@test.com", "b@test.com", "c@test.com", "d@test.com"};
        Long[] memberIds = {1L, 2L, 3L, 4L};

        int successCount = 0;

        for (String requesterEmail : emails) {
            for (Long targetId : memberIds) {
                MemberPortfolioResponse response = rankingService.getMemberPortfolio(1L, targetId, requesterEmail);

                // 기본 검증: 응답 존재, memberId 일치
                assertThat(response).isNotNull();
                assertThat(response.memberId()).isEqualTo(targetId);
                assertThat(response.totalAssets()).isNotNull();
                assertThat(response.cashPercent()).isNotNull();

                // 비중 합계 검증
                BigDecimal totalPct = response.cashPercent();
                for (MemberPortfolioResponse.PortfolioItem item : response.holdings()) {
                    totalPct = totalPct.add(item.percent());
                }
                assertThat(totalPct.doubleValue()).isBetween(99.90, 100.10);

                successCount++;
            }
        }

        // 4명 × 4명 = 16 조합 (자기 자신 포함)
        assertThat(successCount).isEqualTo(16);
    }
}
