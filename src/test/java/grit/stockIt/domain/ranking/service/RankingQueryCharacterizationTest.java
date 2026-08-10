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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.annotation.DirtiesContext;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RankingService 조회 경로(getMainRankings/getContestRankings/getMyRank) 특성화 테스트
 * (Phase A, 프로덕션 무수정). 현재 관찰 가능한 동작을 그대로 동결한다.
 *
 * 하네스는 RankingUpdateCharacterizationTest/RankingCacheCharacterizationTest(W1)에서 확립한
 * 패턴을 그대로 재사용한다 (ApplicationEventPublisher/Environment @SpyBean 불가 등은 이 클래스가
 * 사용하지 않으므로 재서술하지 않음).
 *
 * DB 격리 전략: 이 클래스 내 테스트 메서드 사이에는 트랜잭션 롤백이 없어 데이터가 누적된다.
 * - findByContest(contest)/countByContest_ContestId(contestId)는 매 테스트가 새 UUID 접미
 *   Contest를 사용하므로 자연히 격리된다 (스텁 불필요).
 * - findMainAccountsOrderByBalance()/countMainAccounts()는 DB 전역 Main 계좌를 대상으로 하므로
 *   전체 인원수·순위 시퀀스의 정확한 단언이 필요한 시나리오(Q1 필터링 확인 제외)에서는
 *   해당 리스트에 포함되는지 여부만 memberId로 추출해 검증하거나, 필요한 경우에만
 *   @SpyBean doReturn으로 스텁한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("RankingService 조회(getMainRankings/getContestRankings/getMyRank) 특성화 테스트 (통합 테스트)")
class RankingQueryCharacterizationTest extends IntegrationTestSupport {

    @Autowired
    private RankingService rankingService;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private CacheManager cacheManager;

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

    // ===== 픽스처 헬퍼 =====

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Member createMember() {
        String id = uniqueSuffix();
        Member member = Member.builder()
                .name("쿼리특성화" + id)
                .email("ranking-query-" + id + "@test.com")
                .provider(AuthProvider.LOCAL)
                .build();
        return memberRepository.save(member);
    }

    private Contest createContest() {
        LocalDateTime now = LocalDateTime.now();
        Contest contest = Contest.builder()
                .contestName("쿼리특성화 대회 " + uniqueSuffix())
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
                .code("Q" + uniqueSuffix())
                .name("쿼리특성화종목 " + uniqueSuffix())
                .marketType("KOSPI")
                .build();
        return stockRepository.save(stock);
    }

    private AccountStock createHolding(Account account, Stock stock, int quantity, BigDecimal avgPrice) {
        return accountStockRepository.save(AccountStock.create(account, stock, quantity, avgPrice));
    }

    /** 응답 랭킹 리스트에서 memberId에 해당하는 항목만 추출한다 (DB 전역 누적 데이터로부터 격리). */
    private Optional<RankingItemResponse> findItem(List<RankingItemResponse> rankings, Long memberId) {
        return rankings.stream().filter(dto -> dto.getMemberId().equals(memberId)).findFirst();
    }

    // ===== Q1: Main 랭킹 - isDefault=true 계좌만, 총자산 내림차순, sortBy/contestId/contestName =====

    @Test
    @DisplayName("Q1: getMainRankings는 isDefault=true 계좌만 총자산 내림차순으로 포함하고 메타필드가 고정값이다")
    void q1_mainRankings_onlyDefaultAccounts_descByTotalAssets_fixedMeta() {
        Member memberA = createMember();
        Member memberB = createMember();
        Member memberC = createMember();
        Contest contest = createContest();
        Account accountA = createMainAccount(memberA, contest, new BigDecimal("5000000"));
        Account accountB = createMainAccount(memberB, contest, new BigDecimal("3000000"));
        // 비-default(대회) 계좌: 총자산이 압도적으로 커도 Main 랭킹에 절대 포함되면 안 됨
        createContestAccount(memberC, contest, new BigDecimal("9999999999"));

        RankingResponse response = rankingService.getMainRankings();

        assertThat(response.getContestId()).isNull();
        assertThat(response.getContestName()).isEqualTo("Main 계좌");
        assertThat(response.getSortBy()).isEqualTo("totalAssets");

        Optional<RankingItemResponse> itemA = findItem(response.getRankings(), memberA.getMemberId());
        Optional<RankingItemResponse> itemB = findItem(response.getRankings(), memberB.getMemberId());
        Optional<RankingItemResponse> itemC = findItem(response.getRankings(), memberC.getMemberId());

        assertThat(itemA).isPresent();
        assertThat(itemB).isPresent();
        assertThat(itemC).isEmpty(); // 비-default 계좌 제외 확인
        assertThat(itemA.get().getTotalAssets()).isEqualByComparingTo("5000000");
        assertThat(itemB.get().getTotalAssets()).isEqualByComparingTo("3000000");
        assertThat(itemA.get().getRank()).isLessThan(itemB.get().getRank()); // 총자산 내림차순
        assertThat(accountA).isNotNull();
    }

    // ===== Q2: Main 랭킹 2차호출 @Cacheable 히트 =====

    @Test
    @DisplayName("Q2: getMainRankings 2차 호출은 캐시 히트로 collector-seam 재호출이 없다")
    void q2_mainRankings_secondCall_cacheHit_noAdditionalCollectorCall() {
        Member member = createMember();
        Contest contest = createContest();
        createMainAccount(member, contest, new BigDecimal("1000000"));

        RankingResponse first = rankingService.getMainRankings();
        assertThat(first.getRankings()).isNotEmpty();
        verify(accountStockRepository, times(1)).findDistinctStockCodes();

        RankingResponse second = rankingService.getMainRankings();
        assertThat(second).isNotNull();
        verify(accountStockRepository, times(1)).findDistinctStockCodes();
    }

    // ===== Q3: getContestRankings(id,"balance") → sortBy 정규화 후 totalAssets 응답 =====

    @Test
    @DisplayName("Q3: getContestRankings(id,\"balance\")는 sortBy를 totalAssets로 정규화한 응답을 반환한다")
    void q3_contestRankings_balanceSortBy_normalizedToTotalAssets() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("2000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "balance");

        assertThat(response.getSortBy()).isEqualTo("totalAssets");
        assertThat(response.getContestId()).isEqualTo(contest.getContestId());
        assertThat(response.getContestName()).isEqualTo(contest.getContestName());
    }

    // ===== Q4: getContestRankings(id,"returnRate") → returnRate 정렬, 필드 분리 =====

    @Test
    @DisplayName("Q4: getContestRankings(id,\"returnRate\")는 수익률로 정렬하고 totalAssets=실제총자산/returnRate=수익률을 채운다")
    void q4_contestRankings_returnRate_sortByReturnRate_fieldsSeparated() {
        Member memberHigh = createMember();
        Member memberLow = createMember();
        Contest contest = createContest(); // seedMoney 10,000,000
        Account accountHigh = createContestAccount(memberHigh, contest, new BigDecimal("15000000")); // +50%
        Account accountLow = createContestAccount(memberLow, contest, new BigDecimal("8000000")); // -20%

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "returnRate");

        assertThat(response.getSortBy()).isEqualTo("returnRate");
        List<RankingItemResponse> rankings = response.getRankings();
        RankingItemResponse itemHigh = findItem(rankings, memberHigh.getMemberId()).orElseThrow();
        RankingItemResponse itemLow = findItem(rankings, memberLow.getMemberId()).orElseThrow();

        assertThat(itemHigh.getRank()).isLessThan(itemLow.getRank());
        assertThat(itemHigh.getTotalAssets()).isEqualByComparingTo("15000000"); // 필드는 실제 총자산
        assertThat(itemHigh.getReturnRate()).isEqualByComparingTo("50.00");     // 필드는 수익률
        assertThat(itemLow.getTotalAssets()).isEqualByComparingTo("8000000");
        assertThat(itemLow.getReturnRate()).isEqualByComparingTo("-20.00");
        assertThat(accountHigh).isNotNull();
        assertThat(accountLow).isNotNull();
    }

    // ===== Q5: 캐시 키 분리 - sortBy별 독립 캐시 엔트리 =====

    @Test
    @DisplayName("Q5: 대회 랭킹은 'contest:id:sortBy' 키로 sortBy별 독립적인 캐시 엔트리를 가진다")
    void q5_contestRankings_cacheKeySeparatedBySortBy() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("2000000"));

        rankingService.getContestRankings(contest.getContestId(), "totalAssets");
        rankingService.getContestRankings(contest.getContestId(), "returnRate");

        Cache rankings = cacheManager.getCache("rankings");
        Cache.ValueWrapper totalAssetsEntry = rankings.get("contest:" + contest.getContestId() + ":totalAssets");
        Cache.ValueWrapper returnRateEntry = rankings.get("contest:" + contest.getContestId() + ":returnRate");

        assertThat(totalAssetsEntry).isNotNull();
        assertThat(returnRateEntry).isNotNull();
        assertThat(((RankingResponse) totalAssetsEntry.get()).getSortBy()).isEqualTo("totalAssets");
        assertThat(((RankingResponse) returnRateEntry.get()).getSortBy()).isEqualTo("returnRate");
    }

    // ===== Q6: getMyRank(main, contestId=null) → balanceRank 채움, returnRateRank/myReturnRate=null =====

    @Test
    @DisplayName("Q6: getMyRank(main)은 balanceRank만 채우고 returnRateRank/myReturnRate는 null이다")
    void q6_getMyRank_main_fillsBalanceRankOnly() {
        Member member = createMember();
        Contest contest = createContest();
        Account account = createMainAccount(member, contest, new BigDecimal("1234000"));

        MyRankResponse response = rankingService.getMyRank(member.getMemberId(), null);

        assertThat(response.getBalanceRank()).isNotNull();
        assertThat(response.getReturnRateRank()).isNull();
        assertThat(response.getMyReturnRate()).isNull();
        assertThat(response.getMyBalance()).isEqualByComparingTo(account.getCash());
        assertThat(response.getMyTotalAssets()).isEqualByComparingTo(account.getCash());
    }

    // ===== Q7: getMyRank(contest) → balanceRank+returnRateRank+myReturnRate 모두 채움 =====

    @Test
    @DisplayName("Q7: getMyRank(contest)은 balanceRank/returnRateRank/myReturnRate를 모두 채운다")
    void q7_getMyRank_contest_fillsAllRanksAndReturnRate() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("12000000")); // +20%

        MyRankResponse response = rankingService.getMyRank(member.getMemberId(), contest.getContestId());

        assertThat(response.getBalanceRank()).isNotNull();
        assertThat(response.getReturnRateRank()).isNotNull();
        assertThat(response.getMyReturnRate()).isEqualByComparingTo("20.00");
    }

    // ===== Q8: getMyRank 존재하지 않는 대회 → IllegalArgumentException =====

    @Test
    @DisplayName("Q8: getMyRank는 (계좌는 있지만) 대회를 찾을 수 없으면 '대회를 찾을 수 없습니다.' 예외를 던진다")
    void q8_getMyRank_contestNotFound_throwsIllegalArgumentException() {
        // findMyAccount가 먼저 실행되므로 실제 FK 무결성상 계좌가 존재하면 그 계좌의 contestId는
        // 항상 실재하는 대회를 가리킨다(고아 계좌 불가). 서비스 코드의 "대회를 찾을 수 없습니다."
        // 분기(4단계 contestRepository.findById)를 도달시키기 위해, findMyAccount의 리포지토리
        // 호출만 스텁하여 존재하지 않는 contestId로도 계좌 조회가 성공한 것처럼 만든다.
        Member member = createMember();
        Contest realContest = createContest();
        Account account = createContestAccount(member, realContest, new BigDecimal("1000000"));
        long nonExistentContestId = -999L;
        doReturn(Optional.of(account)).when(accountRepository)
                .findByMemberIdAndContestId(member.getMemberId(), nonExistentContestId);

        assertThatThrownBy(() -> rankingService.getMyRank(member.getMemberId(), nonExistentContestId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대회를 찾을 수 없습니다. (ID: " + nonExistentContestId + ")");
    }

    // ===== Q9: getMyRank main 계좌 없음 → IllegalArgumentException =====

    @Test
    @DisplayName("Q9: getMyRank는 Main 계좌가 없으면 'Main 계좌를 찾을 수 없습니다.' 예외를 던진다")
    void q9_getMyRank_noMainAccount_throwsIllegalArgumentException() {
        Member member = createMember(); // 계좌 없음

        assertThatThrownBy(() -> rankingService.getMyRank(member.getMemberId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Main 계좌를 찾을 수 없습니다.");
    }

    // ===== Q10: getMyRank 대회 계좌 없음 → IllegalArgumentException =====

    @Test
    @DisplayName("Q10: getMyRank는 대회 계좌가 없으면 '대회 계좌를 찾을 수 없습니다.' 예외를 던진다")
    void q10_getMyRank_noContestAccount_throwsIllegalArgumentException() {
        Member member = createMember(); // 대회 계좌 없음
        Contest contest = createContest();

        assertThatThrownBy(() -> rankingService.getMyRank(member.getMemberId(), contest.getContestId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("대회 계좌를 찾을 수 없습니다.");
    }

    // ===== Q11: 동률(총자산) 3계좌 → rank 시퀀스 [1,1,1,4] (경쟁 랭킹) =====

    @Test
    @DisplayName("Q11: 총자산 동률 3계좌는 경쟁 랭킹 규칙으로 [1,1,1,4] 순위를 받는다")
    void q11_tiedTotalAssets_competitionRankingSequence() {
        Contest contest = createContest();
        BigDecimal tiedCash = new BigDecimal("7000000");
        Member m1 = createMember();
        Member m2 = createMember();
        Member m3 = createMember();
        Member m4 = createMember();
        createContestAccount(m1, contest, tiedCash);
        createContestAccount(m2, contest, tiedCash);
        createContestAccount(m3, contest, tiedCash);
        createContestAccount(m4, contest, new BigDecimal("1000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        List<Integer> ranks = response.getRankings().stream().map(RankingItemResponse::getRank).toList();
        assertThat(ranks).containsExactly(1, 1, 1, 4);
    }

    // ===== Q12: 동률(수익률) 3계좌 → rank 시퀀스 [1,1,1,4] =====

    @Test
    @DisplayName("Q12: 수익률 동률 3계좌도 경쟁 랭킹 규칙으로 [1,1,1,4] 순위를 받는다")
    void q12_tiedReturnRate_competitionRankingSequence() {
        Contest contest = createContest();
        BigDecimal tiedCash = new BigDecimal("11000000"); // 동일 수익률
        Member m1 = createMember();
        Member m2 = createMember();
        Member m3 = createMember();
        Member m4 = createMember();
        createContestAccount(m1, contest, tiedCash);
        createContestAccount(m2, contest, tiedCash);
        createContestAccount(m3, contest, tiedCash);
        createContestAccount(m4, contest, new BigDecimal("1000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "returnRate");

        List<Integer> ranks = response.getRankings().stream().map(RankingItemResponse::getRank).toList();
        assertThat(ranks).containsExactly(1, 1, 1, 4);
    }

    // ===== Q13: findMyRankInList 미존재 memberId → null rank =====

    @Test
    @DisplayName("Q13: findMyRankInList는 랭킹 리스트에 없는 memberId에 대해 null 순위를 반환한다")
    void q13_findMyRankInList_memberNotInRankingList_returnsNullRank() {
        // getMyRank 내부의 getMainRankings() 호출은 같은 인스턴스 자기호출이라 @Cacheable 프록시를
        // 우회해 항상 실시간 재계산한다(Q16에서 동결한 동작). 따라서 "캐시가 실제로 있다"만으로는
        // findMyRankInList의 미존재 분기를 재현할 수 없다 — findMyAccount(단건 조회)는 실제 계좌를
        // 찾지만 findMainAccountsOrderByBalance(목록 조회)는 그 계좌를 포함하지 않는 상황을
        // AccountRepository @SpyBean으로 직접 재현한다(목록 쿼리만 스텁, 단건 쿼리는 실제 그대로 둠).
        Member member = createMember();
        Contest contest = createContest();
        createMainAccount(member, contest, new BigDecimal("600000"));
        doReturn(List.of()).when(accountRepository).findMainAccountsOrderByBalance();

        MyRankResponse response = rankingService.getMyRank(member.getMemberId(), null);

        assertThat(response.getBalanceRank()).isNull();
    }

    // ===== Q14: 티어 enrichment - MissionQueryService 정상 티어 → DTO.tier 채움 =====

    @Test
    @DisplayName("Q14: MissionQueryService가 정상 티어를 반환하면 랭킹 아이템 tier에 채워진다")
    void q14_tierEnrichment_normalTier_fillsDto() {
        when(missionQueryService.getTierInfo(anyString()))
                .thenReturn(UserTierStatusResponse.builder().currentTier("GOLD 2").build());
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("1000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(item.getTier()).isEqualTo("GOLD 2");
    }

    // ===== Q15: representativeTitle null 멤버 → title/titleId null =====

    @Test
    @DisplayName("Q15: representativeTitle이 없는 멤버는 랭킹 아이템의 title/titleId가 null이다")
    void q15_noRepresentativeTitle_dtoTitleFieldsNull() {
        Member member = createMember(); // representativeTitle 미설정
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("1000000"));

        RankingResponse response = rankingService.getContestRankings(contest.getContestId(), "totalAssets");

        RankingItemResponse item = findItem(response.getRankings(), member.getMemberId()).orElseThrow();
        assertThat(item.getRepresentativeTitle()).isNull();
        assertThat(item.getRepresentativeTitleId()).isNull();
    }

    // ===== Q16: 자기호출 우회 고정 - collector-seam 누적 times(2)/times(3) =====

    @Test
    @DisplayName("Q16(자기호출 우회 고정): getMyRank(main)은 캐시 워밍 여부와 무관하게 collector-seam이 누적 2회 호출된다")
    void q16_getMyRank_main_selfInvocationBypassesProxy_collectorSeamCalledTwice() {
        Member member = createMember();
        Contest contest = createContest();
        createMainAccount(member, contest, new BigDecimal("1000000"));

        // 캐시 워밍 (실제로는 자기호출 우회로 인해 결과가 재사용되지 않음을 이 테스트가 동결한다)
        rankingService.getMainRankings();
        reset(accountStockRepository);

        rankingService.getMyRank(member.getMemberId(), null);

        // 자체 collectAllHeldStockCodes() 1회 + getMyRank 내부에서 호출하는 getMainRankings()의
        // @Cacheable 프록시 우회(자기호출)로 인한 재계산 1회 = 누적 2회
        verify(accountStockRepository, times(2)).findDistinctStockCodes();
    }

    @Test
    @DisplayName("Q16(자기호출 우회 고정): getMyRank(contest)는 캐시 워밍 여부와 무관하게 collector-seam이 누적 3회 호출된다")
    void q16_getMyRank_contest_selfInvocationBypassesProxy_collectorSeamCalledThrice() {
        Member member = createMember();
        Contest contest = createContest();
        createContestAccount(member, contest, new BigDecimal("1000000"));

        // 캐시 워밍
        rankingService.getContestRankings(contest.getContestId(), "totalAssets");
        rankingService.getContestRankings(contest.getContestId(), "returnRate");
        reset(accountStockRepository);

        rankingService.getMyRank(member.getMemberId(), contest.getContestId());

        // 자체 1회 + getContestRankings(totalAssets) 자기호출 재계산 1회
        // + getContestRankings(returnRate) 자기호출 재계산 1회 = 누적 3회
        verify(accountStockRepository, times(3)).findDistinctStockCodes();
    }
}
