package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
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
import grit.stockIt.domain.ranking.dto.RankingResponse;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RankingService Caffeine 캐시("rankings") 특성화 테스트 (Phase A, 프로덕션 무수정).
 * 현재 관찰 가능한 캐시 동작을 그대로 동결한다.
 *
 * collector-seam: AccountStockRepository.findDistinctStockCodes()는 캐시가능(@Cacheable) 공개
 * 메서드(getMainRankings/getContestRankings)가 실제로 재계산할 때만(=캐시 미스) 호출되므로,
 * 이 seam의 누적 호출 수로 "캐시 히트/미스/재적재" 라이프사이클을 능동적으로 검증한다.
 *
 * gate 개방: Environment는 @SpyBean이 불가능하다(Spring이 beanFactory.registerSingleton()으로
 * BeanDefinition을 우회해 직접 등록하므로 MockitoPostProcessor가 대체하지 못함 — 실측 확인,
 * RankingUpdateCharacterizationTest 상단 주석 참조). 대신 실 ConfigurableEnvironment의
 * PropertySources 최상단에 spring.task.scheduling.enabled=true를 임시로 addFirst()한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("RankingService rankings 캐시 특성화 테스트 (통합 테스트)")
class RankingCacheCharacterizationTest extends IntegrationTestSupport {

    private static final String GATE_OVERRIDE_SOURCE_NAME = "ranking-cache-gate-override";

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
                .name("캐시특성화" + id)
                .email("ranking-cache-" + id + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build();
        return memberRepository.save(member);
    }

    private Contest createContest() {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = Contest.builder()
                .contestName("캐시특성화 대회 " + uniqueSuffix())
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

    private void openSchedulingGate() {
        configurableEnvironment.getPropertySources().addFirst(
                new MapPropertySource(GATE_OVERRIDE_SOURCE_NAME,
                        Map.of("spring.task.scheduling.enabled", "true")));
    }

    // ===== C1: 실 CacheManager getCache("rankings") non-null =====

    @Test
    @DisplayName("C1: 실 CacheManager에서 'rankings' 캐시가 활성화되어 있다")
    void c1_rankingsCacheIsRegisteredAndNonNull() {
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings).isNotNull();
        assertThat(rankings.getName()).isEqualTo("rankings");
    }

    // ===== C2: 캐시 히트 → updateAllRankings @CacheEvict → 캐시 미스 재계산 (라이프사이클) =====

    @Test
    @DisplayName("C2: getMainRankings 캐시 히트 후 updateAllRankings로 evict되면 다음 호출은 캐시 미스로 재계산한다")
    void c2_cacheHitThenEvictThenMissRecomputes_verifiedByCollectorSeam() {
        Member member = createMember();
        Contest contest = createContest();
        Account mainAccount = createMainAccount(member, contest, new BigDecimal("1000000"));
        doReturn(List.of(mainAccount)).when(accountRepository).findMainAccountsOrderByBalance();
        doReturn(List.of()).when(contestRepository).findActiveContests(any());

        // 1. 최초 호출 → 캐시 미스 → collector-seam 1회
        RankingResponse first = rankingService.getMainRankings();
        assertThat(first.getRankings()).isNotEmpty();
        verify(accountStockRepository, times(1)).findDistinctStockCodes();

        // 2. 재호출 → 캐시 히트 → collector-seam 추가 호출 없음 (여전히 1회)
        RankingResponse second = rankingService.getMainRankings();
        assertThat(second).isNotNull();
        verify(accountStockRepository, times(1)).findDistinctStockCodes();

        // 3. 배치 실행(@CacheEvict allEntries) → 배치 자체도 collector-seam을 1회 호출 → 누적 2회
        openSchedulingGate();
        rankingService.updateAllRankings();
        verify(accountStockRepository, times(2)).findDistinctStockCodes();
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings.get("main:balance")).isNull();

        // 4. evict 이후 재호출 → 캐시 미스 재계산 → collector-seam 누적 3회
        RankingResponse third = rankingService.getMainRankings();
        assertThat(third.getRankings()).isNotEmpty();
        verify(accountStockRepository, times(3)).findDistinctStockCodes();
        assertThat(rankings.get("main:balance")).isNotNull();
    }

    // ===== C3: @BeforeEach clear 격리 재현 =====

    @Test
    @DisplayName("C3: @BeforeEach의 캐시 clear로 이전 테스트의 캐시 엔트리가 남아있지 않는다")
    void c3_beforeEachClear_reproducesIsolationAcrossTests() {
        Cache rankings = cacheManager.getCache("rankings");
        // C2에서 'main:balance' 키를 채웠더라도 @BeforeEach의 clear()로 인해 이 시점엔 비어 있어야 한다.
        assertThat(rankings.get("main:balance")).isNull();

        Member member = createMember();
        Contest contest = createContest();
        Account mainAccount = createMainAccount(member, contest, new BigDecimal("500000"));
        doReturn(List.of(mainAccount)).when(accountRepository).findMainAccountsOrderByBalance();

        RankingResponse response = rankingService.getMainRankings();
        assertThat(response.getRankings()).isNotEmpty();
        assertThat(rankings.get("main:balance")).isNotNull();
    }
}
