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
import grit.stockIt.domain.mission.event.RankerAchievedEvent;
import grit.stockIt.domain.mission.service.MissionQueryService;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RankingService.updateAllRankings (1분 배치) 특성화 테스트 (Phase A, 프로덕션 무수정).
 * 현재 관찰 가능한 동작을 그대로 동결한다. 버그로 보이는 지점도 수정하지 않고 현재 동작대로 단언한다.
 *
 * manual gate: application-test.yml/@TestPropertySource가 spring.task.scheduling.enabled=false로
 * 고정하므로, 배치 게이트를 열어야 하는 시나리오는 실 ConfigurableEnvironment의 PropertySources
 * 최상단에 spring.task.scheduling.enabled=true를 임시로 addFirst()해 수동으로 updateAllRankings()를
 * 직접 호출한다(@TestPropertySource 자체를 재정의하지 않음 — 실제 스케줄러가 살아나면 안 됨.
 * TestSchedulingConfig의 no-op TaskScheduler가 발화 자체를 억제한다).
 *
 * 근거 있는 하네스 이탈(실측 확인, 계획 문서의 @SpyBean 결정과 다름):
 * - ApplicationEventPublisher는 @SpyBean 불가능 — Spring 컨텍스트는 이를 일반 빈이 아니라
 *   resolvableDependency(ApplicationContext 자기 자신)로 주입하므로, 스파이가 감쌀 기존 빈이 없어
 *   인터페이스를 직접 인스턴스화하려다 BeanInstantiationException이 발생한다. 대신 실제 컨텍스트에
 *   이벤트 리스너 빈(RankerAchievedEventCaptor)을 추가로 등록해 real publish 이벤트를 그대로 캡처한다.
 * - Environment도 @SpyBean 불가능 — Spring이 beanFactory.registerSingleton()으로 BeanDefinition 우회
 *   직접 등록하므로 MockitoPostProcessor가 대체하지 못하고, 주입된 필드가 여전히 원본 인스턴스라
 *   Mockito.reset() 시 NotAMockException이 발생한다(실측 확인). 대신 실 ConfigurableEnvironment의
 *   PropertySources를 직접 조작한다.
 *
 * AccountRepository/ContestRepository는 findMainAccountsOrderByBalance/findActiveContests를
 * doReturn으로 직접 스텁해 컨테이너 DB에 누적되는 형제 테스트 메서드의 계좌/대회 데이터로부터
 * 완전히 격리한다 (같은 클래스 내 테스트 메서드 사이에 트랜잭션 롤백이 없어 데이터가 누적되므로).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("RankingService 배치 갱신(updateAllRankings) 특성화 테스트 (통합 테스트)")
class RankingUpdateCharacterizationTest extends IntegrationTestSupport {

    private static final String GATE_OVERRIDE_SOURCE_NAME = "ranking-update-gate-override";

    @TestConfiguration
    static class RankerAchievedEventCaptureConfig {
        @Bean
        RankerAchievedEventCaptor rankerAchievedEventCaptor() {
            return new RankerAchievedEventCaptor();
        }
    }

    /** RankingService가 발행하는 RankerAchievedEvent를 실제 이벤트 버스를 통해 그대로 캡처하는 테스트 전용 리스너. */
    static class RankerAchievedEventCaptor {
        private final List<RankerAchievedEvent> captured = Collections.synchronizedList(new ArrayList<>());

        @EventListener
        void onRankerAchieved(RankerAchievedEvent event) {
            captured.add(event);
        }

        List<RankerAchievedEvent> captured() {
            return captured;
        }

        void reset() {
            captured.clear();
        }
    }

    @Autowired
    private RankingService rankingService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RankerAchievedEventCaptor rankerAchievedEventCaptor;

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
        // @SpyBean/@MockBean은 캐시된 컨텍스트에서 테스트 간 공유되므로, 각 테스트 시작 시
        // 호출기록·스텁을 초기화한다(격리).
        reset(accountRepository, contestRepository, accountStockRepository, redisMarketDataRepository);
        rankerAchievedEventCaptor.reset();
        when(missionQueryService.getTierInfo(anyString()))
                .thenReturn(UserTierStatusResponse.builder().currentTier("BRONZE 1").build());
        // 컨테이너 DB에 형제 테스트가 남긴 보유종목이 있어도(캐시미스) 실패하지 않도록 기본 스텁.
        when(stockDetailService.getCurrentPrice(anyString()))
                .thenReturn(Mono.just(new BigDecimal("10000")));
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings).isNotNull();
        rankings.clear();
    }

    @AfterEach
    void tearDown() {
        // 게이트 오버라이드 PropertySource가 남아 다음 테스트로 누출되지 않도록 제거한다.
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
                .name("배치특성화" + id)
                .email("ranking-update-" + id + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build();
        return memberRepository.save(member);
    }

    private Contest createContest(boolean active) {
        LocalDateTime now = LocalDateTime.now();
        Contest.ContestBuilder builder = Contest.builder()
                .contestName("배치특성화 대회 " + uniqueSuffix())
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .isDefault(false);
        if (active) {
            builder.startDate(now.minusDays(1)).endDate(now.plusDays(1));
        } else {
            builder.startDate(now.plusDays(5)).endDate(now.plusDays(10));
        }
        return contestRepository.save(builder.build());
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

    private void openSchedulingGate() {
        configurableEnvironment.getPropertySources().addFirst(
                new MapPropertySource(GATE_OVERRIDE_SOURCE_NAME,
                        Map.of("spring.task.scheduling.enabled", "true")));
    }

    // ===== U1: 게이트 개방 → main + active contest 랭킹 생성, no throw =====

    @Test
    @DisplayName("U1: 게이트 개방 시 main+진행중 대회 랭킹이 예외 없이 생성된다")
    void u1_gateOpen_batchRunsMainAndActiveContest_noThrow() {
        openSchedulingGate();
        Member member = createMember();
        Member contestMember = createMember();
        Contest contest = createContest(true);
        Account mainAccount = createMainAccount(member, contest, new BigDecimal("1000000"));
        Account contestAccount = createContestAccount(contestMember, contest, new BigDecimal("2000000"));
        doReturn(List.of(mainAccount)).when(accountRepository).findMainAccountsOrderByBalance();
        doReturn(List.of(contest)).when(contestRepository).findActiveContests(any());

        assertThatCode(() -> rankingService.updateAllRankings()).doesNotThrowAnyException();

        // 배치는 캐시가능(@Cacheable) public 메서드가 아니라 private *WithPrices()를 직접 호출하므로
        // @CacheEvict만 발화하고 캐시를 재적재하지 않는다 (현재 동작 동결).
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings.get("main:balance")).isNull();
        assertThat(contestAccount).isNotNull();
    }

    // ===== U2: 게이트 미개방 → 조기리턴, priceCollector/eventPublisher never, evict는 발화 =====

    @Test
    @DisplayName("U2: 게이트 미개방 시 조기리턴하며 가격수집·이벤트발행은 없지만 캐시는 무효화된다")
    void u2_gateClosed_earlyReturn_priceCollectorAndEventNeverFired_butCacheEvicted() {
        // gate 오버라이드 없음 → application-test.yml의 spring.task.scheduling.enabled=false 그대로 사용
        Member member = createMember();
        Contest contest = createContest(true);
        Account mainAccount = createMainAccount(member, contest, new BigDecimal("1000000"));
        doReturn(List.of(mainAccount)).when(accountRepository).findMainAccountsOrderByBalance();

        // 사전 캐시 워밍 (getMainRankings()는 @Cacheable 공개 메서드 경로)
        rankingService.getMainRankings();
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings.get("main:balance")).isNotNull();

        // 워밍 과정에서 발생한 호출기록을 초기화 후, 배치 조기리턴 시 추가 호출이 없음을 단언
        reset(accountStockRepository, redisMarketDataRepository);
        rankerAchievedEventCaptor.reset();

        assertThatCode(() -> rankingService.updateAllRankings()).doesNotThrowAnyException();

        verify(accountStockRepository, never()).findDistinctStockCodes();
        verify(redisMarketDataRepository, never()).getLastPrice(anyString());
        assertThat(rankerAchievedEventCaptor.captured()).isEmpty();

        // @CacheEvict(allEntries=true)는 AOP상 메서드 정상 반환(조기 return 포함) 후 발화 → 캐시 무효화됨
        assertThat(rankings.get("main:balance")).isNull();
    }

    // ===== U3: Top10 → RankerAchievedEvent 1회 발행, rank<=10만 포함 =====

    @Test
    @DisplayName("U3: Main 랭킹 12명 중 Top10만 RankerAchievedEvent로 1회 발행된다")
    void u3_top10_publishesRankerAchievedEventOnceWithRankLE10Only() {
        openSchedulingGate();
        List<Account> accounts = new ArrayList<>();
        List<Long> expectedTop10 = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Member member = createMember();
            Contest contest = createContest(false);
            // 내림차순 총자산: 순위 1~12 결정 (동률 없음)
            BigDecimal cash = new BigDecimal(2_000_000_000L - (long) i * 100_000_000L);
            Account account = createMainAccount(member, contest, cash);
            accounts.add(account);
            if (i < 10) {
                expectedTop10.add(member.getMemberId());
            }
        }
        doReturn(accounts).when(accountRepository).findMainAccountsOrderByBalance();
        doReturn(List.of()).when(contestRepository).findActiveContests(any());

        rankingService.updateAllRankings();

        assertThat(rankerAchievedEventCaptor.captured()).hasSize(1);
        assertThat(rankerAchievedEventCaptor.captured().get(0).top10MemberIds())
                .hasSize(10)
                .containsExactlyInAnyOrderElementsOf(expectedTop10);
    }

    // ===== U4: Top10 empty → 이벤트 never =====

    @Test
    @DisplayName("U4: Main 계좌가 없으면 RankerAchievedEvent가 발행되지 않는다")
    void u4_noMainAccounts_neverPublishesRankerAchievedEvent() {
        openSchedulingGate();
        doReturn(List.of()).when(accountRepository).findMainAccountsOrderByBalance();
        doReturn(List.of()).when(contestRepository).findActiveContests(any());

        assertThatCode(() -> rankingService.updateAllRankings()).doesNotThrowAnyException();

        assertThat(rankerAchievedEventCaptor.captured()).isEmpty();
    }

    // ===== U5: @CacheEvict allEntries → 사전 채운 rankings 캐시 전부 무효화 =====

    @Test
    @DisplayName("U5: 배치 실행 시 사전에 채운 main/contest 캐시 엔트리가 모두 무효화된다")
    void u5_cacheEvictAllEntries_invalidatesPrewarmedEntries() {
        Member member = createMember();
        Contest contest = createContest(true);
        Account mainAccount = createMainAccount(member, contest, new BigDecimal("1000000"));
        doReturn(List.of(mainAccount)).when(accountRepository).findMainAccountsOrderByBalance();

        // 사전 캐시 워밍: main + contest(totalAssets)
        rankingService.getMainRankings();
        rankingService.getContestRankings(contest.getContestId(), "totalAssets");
        Cache rankings = cacheManager.getCache("rankings");
        assertThat(rankings.get("main:balance")).isNotNull();
        assertThat(rankings.get("contest:" + contest.getContestId() + ":totalAssets")).isNotNull();

        openSchedulingGate();
        doReturn(List.of()).when(contestRepository).findActiveContests(any());
        rankingService.updateAllRankings();

        assertThat(rankings.get("main:balance")).isNull();
        assertThat(rankings.get("contest:" + contest.getContestId() + ":totalAssets")).isNull();
    }

    // ===== U6=버그 동결: 배치 중 내부 예외 → catch(Exception) 삼킴, throw 안함 =====

    @Test
    @DisplayName("U6(버그 동결): 배치 중 내부 예외가 발생해도 삼켜지고 updateAllRankings는 throw하지 않는다")
    void u6_internalExceptionDuringBatch_isSwallowed_doesNotThrow() {
        openSchedulingGate();
        doThrow(new RuntimeException("의도된 내부 예외(특성화)"))
                .when(accountStockRepository).findAll();

        assertThatCode(() -> rankingService.updateAllRankings()).doesNotThrowAnyException();

        // 예외가 Main 랭킹 계산(accountStockRepository.findAll()) 단계에서 발생하므로
        // 이후의 Top10 이벤트 발행 단계까지 도달하지 못한다.
        assertThat(rankerAchievedEventCaptor.captured()).isEmpty();
    }

    // ===== U7: 진행 중 대회 totalAssets·returnRate 2회 갱신 호출 =====

    @Test
    @DisplayName("U7: 진행 중인 대회는 totalAssets·returnRate 정렬 각각 1회씩 총 2회 갱신 호출된다")
    void u7_activeContest_calledTwice_totalAssetsAndReturnRate() {
        openSchedulingGate();
        Member member = createMember();
        Contest contest = createContest(true);
        Account mainAccount = createMainAccount(member, contest, new BigDecimal("1000000"));
        doReturn(List.of(mainAccount)).when(accountRepository).findMainAccountsOrderByBalance();
        doReturn(List.of(contest)).when(contestRepository).findActiveContests(any());

        rankingService.updateAllRankings();

        // getContestRankingsWithPrices는 대회당 (totalAssets, returnRate) 각 1회씩 호출되며
        // 매 호출마다 accountRepository.findByContest(contest)를 1회씩 사용한다 → 총 2회.
        verify(accountRepository, times(2)).findByContest(any(Contest.class));
    }
}
