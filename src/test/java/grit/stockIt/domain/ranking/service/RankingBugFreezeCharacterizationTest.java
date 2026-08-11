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
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.dto.UserTierStatusResponse;
import grit.stockIt.domain.mission.service.MissionQueryService;
import grit.stockIt.domain.ranking.dto.MyRankResponse;
import grit.stockIt.domain.ranking.dto.RankingItemResponse;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * RankingService 의심버그 a~k 특성화 테스트 (Phase A, 프로덕션 무수정).
 * 현재 관찰 가능한(버그로 보이는) 동작을 수정하지 않고 그대로 동결한다.
 *
 * 하네스는 RankingQueryCharacterizationTest(W2)/RankingUpdateCharacterizationTest(W1)의 패턴을 그대로 재사용한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("RankingService 의심버그(a~k) 특성화 테스트 (통합 테스트)")
class RankingBugFreezeCharacterizationTest extends IntegrationTestSupport {

    private static final String GATE_OVERRIDE_SOURCE_NAME = "ranking-bugfreeze-gate-override";

    @Autowired
    private RankingService rankingService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ConfigurableEnvironment configurableEnvironment;

    @SpyBean
    private AccountRepository accountRepository;

    @SpyBean
    private ContestRepository contestRepository;

    @SpyBean
    private AccountStockRepository accountStockRepository;

    @SpyBean
    private RedisMarketDataRepository redisMarketDataRepository;

    @Autowired
    private StockRepository stockRepository;

    @MockBean
    private StockDetailService stockDetailService;

    @MockBean
    private MissionQueryService missionQueryService;

    @BeforeEach
    void setUp() {
        reset(accountRepository, contestRepository, accountStockRepository, redisMarketDataRepository);
        when(missionQueryService.getTierInfo(anyString()))
                .thenReturn(UserTierStatusResponse.builder().currentTier("BRONZE 1").build());
        when(stockDetailService.getCurrentPrice(anyString()))
                .thenReturn(Mono.just(new BigDecimal("10000")));
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings).isNotNull();
        rankings.clear();
    }

    @AfterEach
    void tearDown() {
        if (configurableEnvironment.getPropertySources().contains(GATE_OVERRIDE_SOURCE_NAME)) {
            configurableEnvironment.getPropertySources().remove(GATE_OVERRIDE_SOURCE_NAME);
        }
    }

    // ===== 픽스처 헬퍼 =====

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Member createMember() {
        String id = uniqueSuffix();
        Member member = Member.builder()
                .name("버그동결" + id)
                .email("ranking-bugfreeze-" + id + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build();
        return memberRepository.save(member);
    }

    private Contest createContest() {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = Contest.builder()
                .contestName("버그동결 대회 " + uniqueSuffix())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false)
                .startDate(now.minusDays(1))
                .endDate(now.plusDays(1))
                .build();
        return contestRepository.save(contest);
    }

    private Account createMainAccount(Member member, Contest contest, BigDecimal cash) {
        Account account = Account.builder()
                .member(member)
                .contest(contest)
                .accountName("메인계좌 " + uniqueSuffix())
                .cash(cash)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(true)
                .build();
        return accountRepository.save(account);
    }

    private Account createContestAccount(Member member, Contest contest, BigDecimal cash) {
        Account account = Account.builder()
                .member(member)
                .contest(contest)
                .accountName("대회계좌 " + uniqueSuffix())
                .cash(cash)
                .holdAmount(BigDecimal.ZERO)
                .isDefault(false)
                .build();
        return accountRepository.save(account);
    }

    private Stock createStock() {
        Stock stock = Stock.builder()
                .code("B" + uniqueSuffix())
                .name("버그동결종목 " + uniqueSuffix())
                .marketType("KOSPI")
                .build();
        return stockRepository.save(stock);
    }

    private AccountStock createHolding(Account account, Stock stock, int quantity, BigDecimal avgPrice) {
        return accountStockRepository.save(AccountStock.create(account, stock, quantity, avgPrice));
    }

    private Optional<RankingItemResponse> findItem(List<RankingItemResponse> rankings, Long memberId) {
        return rankings.stream().filter(dto -> dto.getMemberId().equals(memberId)).findFirst();
    }

    private void openSchedulingGate() {
        configurableEnvironment.getPropertySources().addFirst(
                new MapPropertySource(GATE_OVERRIDE_SOURCE_NAME,
                        Map.of("spring.task.scheduling.enabled", "true")));
    }

    // ===== a: KIS getCurrentPrice null/0/timeout/예외 → 0원 처리, 총자산=잔액만(왜곡) =====

    @Test
    @DisplayName("a(버그 동결): getCurrentPrice가 empty(null)를 반환하면 해당 종목은 0원 처리된다")
    void a1_currentPrice_emptyMono_treatedAsZero() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createContestAccount(member, contest, new BigDecimal("1000000"));
        Stock stock = createStock();
        createHolding(account, stock, 10, new BigDecimal("5000"));
        when(stockDetailService.getCurrentPrice(stock.getCode())).thenReturn(Mono.empty());

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        // 종목이 0원 처리되어 총자산 = 현금 잔액만 (보유수량 10주분 평가액 미반영, 왜곡)
        assertThat(item.getTotalAssets()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("a(버그 동결): getCurrentPrice가 0원을 반환하면 해당 종목은 0원 처리된다")
    void a2_currentPrice_zero_treatedAsZero() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createContestAccount(member, contest, new BigDecimal("1000000"));
        Stock stock = createStock();
        createHolding(account, stock, 10, new BigDecimal("5000"));
        when(stockDetailService.getCurrentPrice(stock.getCode())).thenReturn(Mono.just(BigDecimal.ZERO));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(item.getTotalAssets()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("a(버그 동결): getCurrentPrice가 예외를 던지면 해당 종목은 0원 처리되고 조회는 실패하지 않는다")
    void a3_currentPrice_exception_treatedAsZero_noThrow() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createContestAccount(member, contest, new BigDecimal("1000000"));
        Stock stock = createStock();
        createHolding(account, stock, 10, new BigDecimal("5000"));
        when(stockDetailService.getCurrentPrice(stock.getCode()))
                .thenReturn(Mono.error(new RuntimeException("의도된 KIS API 예외(특성화)")));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(item.getTotalAssets()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("a(버그 동결): getCurrentPrice가 3초 타임아웃을 초과하면 해당 종목은 0원 처리되고 조회는 실패하지 않는다")
    void a4_currentPrice_timeout_treatedAsZero_noThrow() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createContestAccount(member, contest, new BigDecimal("1000000"));
        Stock stock = createStock();
        createHolding(account, stock, 10, new BigDecimal("5000"));
        when(stockDetailService.getCurrentPrice(stock.getCode()))
                .thenReturn(Mono.just(new BigDecimal("9999")).delayElement(Duration.ofSeconds(4)));

        assertThatCode(() -> rankingService.getContestRankings(contest.getContestId(), "totalAssets"))
                .doesNotThrowAnyException();
    }

    // ===== b: getMyRank myTotalAssets(즉석 재계산) vs 캐시된 랭킹 totalAssets 불일치 =====

    @Test
    @DisplayName("b(버그 동결): getMyRank의 myTotalAssets(자체 스냅샷)와 캐시된 Main 랭킹 totalAssets(재계산 스냅샷)가 시점가변 가격으로 불일치할 수 있다")
    void b_myTotalAssets_vs_cachedRankingTotalAssets_snapshotMismatch() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createMainAccount(member, contest, new BigDecimal("1000000"));
        Stock stock = createStock();
        createHolding(account, stock, 10, new BigDecimal("5000"));
        // Redis 미스 강제 (실제로도 이 종목코드는 저장된 적이 없어 자연 미스이지만 명시적으로 고정)
        doReturn(Optional.empty()).when(redisMarketDataRepository).getLastPrice(stock.getCode());

        BigDecimal p1 = new BigDecimal("10000"); // getMyRank 자체 batchFetch 시점 가격
        BigDecimal p2 = new BigDecimal("20000"); // getMyRank 내부 getMainRankings() 재계산 시점 가격
        when(stockDetailService.getCurrentPrice(stock.getCode()))
                .thenReturn(Mono.just(p1))
                .thenReturn(Mono.just(p2));

        MyRankResponse response = rankingService.getMyRank(member.getMemberId(), null);

        BigDecimal expectedMyTotalAssets = account.getCash().add(p1.multiply(BigDecimal.valueOf(10)));
        BigDecimal expectedCachedTotalAssets = account.getCash().add(p2.multiply(BigDecimal.valueOf(10)));

        assertThat(response.getMyTotalAssets()).isEqualByComparingTo(expectedMyTotalAssets);
        assertThat(response.getMyTotalAssets()).isNotEqualByComparingTo(expectedCachedTotalAssets);

        RankingResponse cachedMainRankings = rankingService.getMainRankings();
        RankingItemResponse cachedItem = findItem(cachedMainRankings.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(cachedItem.getTotalAssets()).isEqualByComparingTo(expectedCachedTotalAssets);
        assertThat(cachedItem.getTotalAssets()).isNotEqualByComparingTo(response.getMyTotalAssets());
    }

    // ===== c: returnRate 경로 AccountWithAssets.totalAssets 필드 오버로딩 → 정렬은 수익률, 필드는 총자산 =====

    @Test
    @DisplayName("c(버그 동결): returnRate 정렬 내부적으로 AccountWithAssets.totalAssets 필드를 수익률로 재사용하지만 응답 DTO 필드는 실제 총자산/수익률로 분리된다")
    void c_returnRateSort_internalFieldOverloading_dtoFieldsCorrectlySeparated() {
        Member member = createMember();
        Contest contest = createContest(); // seedMoney 10,000,000
        Account account = createContestAccount(member, contest, new BigDecimal("9000000"));
        Stock stock = createStock();
        createHolding(account, stock, 20, new BigDecimal("4000")); // 20 * 10000(기본 스텁가) = 200000

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "returnRate");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        BigDecimal expectedTotalAssets = new BigDecimal("9200000"); // 900만 + 20*10000
        BigDecimal expectedReturnRate = expectedTotalAssets.subtract(BigDecimal.valueOf(10_000_000L))
                .divide(BigDecimal.valueOf(10_000_000L), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP);

        assertThat(item.getTotalAssets()).isEqualByComparingTo(expectedTotalAssets); // 오버로딩된 정렬용 값이 아닌 실제 총자산
        assertThat(item.getReturnRate()).isEqualByComparingTo(expectedReturnRate);
        assertThat(item.getTotalAssets()).isNotEqualByComparingTo(item.getReturnRate());
    }

    // ===== e: getTierForMember MissionQueryService 예외 → tier=null 은폐(예외 전파 안함) =====

    @Test
    @DisplayName("e(버그 동결): MissionQueryService.getTierInfo가 예외를 던지면 tier는 null로 은폐되고 조회는 실패하지 않는다")
    void e_getTierForMember_missionServiceThrows_tierNulled_noThrow() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("1000000"));
        when(missionQueryService.getTierInfo(member.getEmail()))
                .thenThrow(new RuntimeException("의도된 미션 서비스 예외(특성화)"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(item.getTier()).isNull();
    }

    // ===== f: 동률 rank/sameRankCount 연동 (2개 동률군: [1,1,3,3,5]) =====

    @Test
    @DisplayName("f(버그 동결): 두 개의 동률 그룹이 있으면 sameRankCount 누적에 따라 [1,1,3,3,5] 순위가 부여된다")
    void f_multipleTieGroups_rankSequenceReflectsSameRankCount() {
        Contest contest = createContest();
        Member m1 = createMember();
        Member m2 = createMember();
        Member m3 = createMember();
        Member m4 = createMember();
        Member m5 = createMember();
        createContestAccount(m1, contest, new BigDecimal("10000000"));
        createContestAccount(m2, contest, new BigDecimal("10000000"));
        createContestAccount(m3, contest, new BigDecimal("8000000"));
        createContestAccount(m4, contest, new BigDecimal("8000000"));
        createContestAccount(m5, contest, new BigDecimal("5000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        List<Integer> ranks = response.getRankings().stream().map(RankingItemResponse::getRank).toList();
        assertThat(ranks).containsExactly(1, 1, 3, 3, 5);
    }

    // ===== g(수정됨): 정규화 데드라인 제거됨 — 응답 sortBy는 isReturnRate 삼항으로 독립 계산, 관찰 동작은 무변 =====

    @Test
    @DisplayName("g(버그 동결): sortBy=\"balance\"로 요청해도 응답 sortBy는 \"totalAssets\"로 변이되어 반환된다")
    void g_sortByMutation_balanceRequestedButTotalAssetsReturned() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("3000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "balance");

        assertThat(response.getSortBy()).isEqualTo("totalAssets");
    }

    // ===== h: currentPrices.isEmpty() → 잔액만 폴백(레거시 분기), totalAssets=cash =====

    @Test
    @DisplayName("h(버그 동결): 수집된 현재가 Map이 비어있으면 실제 보유 종목이 있어도 총자산=현금 잔액만으로 폴백한다")
    void h_emptyCurrentPricesMap_fallsBackToCashOnly() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createContestAccount(member, contest, new BigDecimal("2000000"));
        Stock stock = createStock();
        createHolding(account, stock, 100, new BigDecimal("3000")); // 실제로는 큰 평가액을 갖는 보유종목

        // collectAllHeldStockCodes()의 결과를 강제로 빈 리스트로 만들어 currentPrices Map을 비운다
        // (레거시 폴백 분기: currentPrices.isEmpty() ? account.getCash() : calculateTotalAssets(...))
        doReturn(List.of()).when(accountStockRepository).findDistinctStockCodes();

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        // 보유종목 평가액(100주 * 가격)이 완전히 무시되고 현금만 반영됨
        assertThat(item.getTotalAssets()).isEqualByComparingTo("2000000");
    }

    // ===== i: updateAllRankings 대회루프가 getContestRankingsWithPrices 반환값 폐기(캐시 워밍 안됨) =====

    @Test
    @DisplayName("i(버그 동결): 배치의 대회 랭킹 계산은 호출되지만 반환값이 폐기되어 캐시가 워밍되지 않는다")
    void i_batchContestLoop_discardsReturnValue_doesNotWarmCache() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("1000000"));
        openSchedulingGate();
        doReturn(List.of()).when(accountRepository).findMainAccountsOrderByBalance();
        doReturn(List.of(contest)).when(contestRepository).findActiveContests(any());

        rankingService.updateAllRankings();

        // getContestRankingsWithPrices는 private *WithPrices() 경로이므로 @Cacheable이 적용되지 않아
        // 배치가 실제로 대회 랭킹을 계산해도(부수효과: 로그) 캐시에는 절대 적재되지 않는다.
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings.get("contest:" + contest.getContestId() + ":totalAssets")).isNull();
        assertThat(rankings.get("contest:" + contest.getContestId() + ":returnRate")).isNull();
    }

    // ===== j: calculateReturnRate(Account,Contest) 데드코드 - 프로덕션 경로 미도달 =====

    @Test
    @DisplayName("j(버그 동결, 문서적 단언): Main/총자산순 대회 랭킹은 항상 includeReturn=false로 호출되어 returnRate가 null이다(레거시 calculateReturnRate 미도달)")
    void j_legacyCalculateReturnRate_unreachableViaMainOrTotalAssetsSort_returnRateAlwaysNull() {
        Member mainMember = createMember();
        Contest contest = createContest();
        createMainAccount(mainMember, contest, new BigDecimal("1000000"));
        Member contestMember = createMember();
        createContestAccount(contestMember, contest, new BigDecimal("2000000"));

        RankingResponse mainResponse = rankingService.getMainRankings();
        RankingResponse totalAssetsResponse = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse mainItem = findItem(mainResponse.getRankings(), mainMember.getMemberId()).orElseThrow();
        RankingItemResponse totalAssetsItem = findItem(totalAssetsResponse.getRankings(), contestMember.getMemberId()).orElseThrow();

        // convertToRankingItemResponsesWithAssets(..., includeReturn=false)만 프로덕션에서 호출되므로
        // calculateReturnRate(Account, Contest)는 도달하지 않고 returnRate는 항상 null이다.
        assertThat(mainItem.getReturnRate()).isNull();
        assertThat(totalAssetsItem.getReturnRate()).isNull();
    }

    // ===== k: getMyRank myReturnRate(스냅샷1) vs contest 랭킹 returnRate(재계산 스냅샷3) 불일치 =====

    @Test
    @DisplayName("k(버그 동결): getMyRank의 myReturnRate(자체 스냅샷)와 캐시된 대회 returnRate 랭킹(재계산 스냅샷)이 시점가변 가격으로 불일치할 수 있다")
    void k_myReturnRate_vs_contestReturnRateRanking_snapshotMismatch() {
        Member member = createMember();
        Contest contest = createContest(); // seedMoney 10,000,000
        Account account = createContestAccount(member, contest, new BigDecimal("5000000"));
        Stock stock = createStock();
        createHolding(account, stock, 10, new BigDecimal("5000"));
        doReturn(Optional.empty()).when(redisMarketDataRepository).getLastPrice(stock.getCode());

        BigDecimal p1 = new BigDecimal("10000"); // getMyRank 자체 batchFetch(myTotalAssets용)
        BigDecimal p2 = new BigDecimal("20000"); // getContestRankings(totalAssets) 자기호출 재계산
        BigDecimal p3 = new BigDecimal("30000"); // getContestRankings(returnRate) 자기호출 재계산
        when(stockDetailService.getCurrentPrice(stock.getCode()))
                .thenReturn(Mono.just(p1))
                .thenReturn(Mono.just(p2))
                .thenReturn(Mono.just(p3));

        MyRankResponse response = rankingService.getMyRank(member.getMemberId(), contest.getContestId());

        BigDecimal myTotalAssets = account.getCash().add(p1.multiply(BigDecimal.valueOf(10))); // 5,100,000
        BigDecimal expectedMyReturnRate = myTotalAssets.subtract(BigDecimal.valueOf(10_000_000L))
                .divide(BigDecimal.valueOf(10_000_000L), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP);

        BigDecimal rankingTotalAssets = account.getCash().add(p3.multiply(BigDecimal.valueOf(10))); // 5,300,000
        BigDecimal expectedRankingReturnRate = rankingTotalAssets.subtract(BigDecimal.valueOf(10_000_000L))
                .divide(BigDecimal.valueOf(10_000_000L), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP);

        assertThat(response.getMyReturnRate()).isEqualByComparingTo(expectedMyReturnRate);
        assertThat(response.getMyReturnRate()).isNotEqualByComparingTo(expectedRankingReturnRate);

        RankingResponse cachedReturnRateRankings = rankingService.getContestRankings(contest.getContestId(), "returnRate");
        RankingItemResponse cachedItem = findItem(cachedReturnRateRankings.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(cachedItem.getReturnRate()).isEqualByComparingTo(expectedRankingReturnRate);
        assertThat(cachedItem.getReturnRate()).isNotEqualByComparingTo(response.getMyReturnRate());
    }
}
