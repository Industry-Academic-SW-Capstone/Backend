package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.mission.event.RankerAchievedEvent;
import grit.stockIt.domain.mission.service.MissionQueryService;
import grit.stockIt.domain.mission.dto.UserTierStatusResponse;
import grit.stockIt.domain.ranking.dto.MyRankResponse;
import grit.stockIt.domain.ranking.dto.RankingItemResponse;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationContext;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
/**
 * 랭킹 서비스
 * - 1분마다 자동으로 랭킹 갱신 (스케줄러)
 * - Main 계좌: 잔액 순위만 제공
 * - 대회 계좌: 잔액 순위 + 수익률 순위 제공
 * - Caffeine 로컬 캐시 사용
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RankingService {

    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final ContestRepository contestRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MissionQueryService missionQueryService;
    private final Environment environment;
    private final RankingCalculationService rankingCalculationService;
    private final RankingPriceCollectionService rankingPriceCollectionService;
    private final ApplicationContext applicationContext;
    // ==================== 스케줄러 ====================

    /**
     * 1분마다 모든 랭킹 자동 갱신
     * - Main 계좌 랭킹 갱신
     * - 진행 중인 모든 대회 랭킹 갱신
     * - 캐시 초기화 후 재생성
     */
    @Scheduled(fixedRate = 60000) // 60초 = 1분
    @CacheEvict(value = "rankings", allEntries = true, beforeInvocation = true)
    @Transactional
    public void updateAllRankings() {
        // 테스트 환경에서는 스케줄러 비활성화
        String schedulingEnabled = environment.getProperty("spring.task.scheduling.enabled", "true");
        if ("false".equals(schedulingEnabled)) {
            return;
        }
        
        log.info("🔄 [스케줄러] 랭킹 갱신 시작: {}", LocalDateTime.now());

        try {
            // self-injection: @Cacheable 프록시를 경유해 실제로 캐시를 워밍한다
            // (private 메서드 직접 호출은 프록시를 타지 않아 캐시가 채워지지 않음 — 버그 i)
            RankingService self = applicationContext.getBean(RankingService.class);

            // 1. Main 계좌 랭킹 갱신 (총자산 기준) — 'main:balance' 캐시 워밍
            RankingResponse mainRanking = self.getMainRankings();
            log.info("Main 계좌 랭킹 갱신 완료");

            // --- [추가] Main 랭킹 Top 10 유저에게 '랭커' 칭호 지급 로직 ---
            if (mainRanking != null && mainRanking.getRankings() != null) {
                List<Long> top10MemberIds = mainRanking.getRankings().stream()
                        .filter(dto -> dto.getRank() <= 10) // 1위~10위 필터링
                        .map(RankingItemResponse::getMemberId)       // MemberId 추출
                        .collect(Collectors.toList());

                // 랭커 달성 이벤트 발행 (동기 리스너가 미션 달성 처리 — 예외는 아래 catch에 흡수)
                if (!top10MemberIds.isEmpty()) {
                    eventPublisher.publishEvent(new RankerAchievedEvent(top10MemberIds));
                }
            }
            // 2. 진행 중인 대회 랭킹 갱신 — 각 대회의 'contest:id:sortBy' 캐시 워밍
            List<Contest> activeContests = contestRepository.findActiveContests(LocalDateTime.now());
            log.info("진행 중인 대회 수: {}", activeContests.size());

            for (Contest contest : activeContests) {
                // 총자산순 랭킹
                self.getContestRankings(contest.getContestId(), "totalAssets");
                // 수익률순 랭킹
                self.getContestRankings(contest.getContestId(), "returnRate");
                log.info("대회 [{}] 랭킹 갱신 완료", contest.getContestName());
            }

            log.info("[스케줄러] 모든 랭킹 갱신 완료: {}", LocalDateTime.now());

        } catch (Exception e) {
            log.error("[스케줄러] 랭킹 갱신 중 오류 발생", e);
        }
    }

    // ==================== Main 계좌 랭킹 ====================

    /**
     * Main 계좌 전체 랭킹 조회 (총자산 기준)
     * - isDefault = true인 계좌만 조회
     * - 로컬 캐시 사용 (70초 TTL)
     *
     * @return RankingResponse (contestId = null, sortBy = "balance")
     */
    @Cacheable(value = "rankings", key = "'main:balance'")
    // 배치가 self-injection 프록시로 이 메서드를 호출할 때 부모(updateAllRankings)와
    // 물리 트랜잭션을 공유하면, 내부 예외가 부모 트랜잭션을 rollback-only로 표시해
    // updateAllRankings의 catch(Exception)로 삼켜져도 커밋 시점에 UnexpectedRollbackException이
    // 새어나간다. REQUIRES_NEW로 격리해 기존 예외-삼킴 동작(U6)을 보존한다.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RankingResponse getMainRankings() {
        log.info("Main 계좌 랭킹 조회 (총자산 기준 - DB에서 로드)");

        // 1. 모든 보유 종목의 현재가 배치 수집
        Set<String> requiredStockCodes = rankingPriceCollectionService.collectAllHeldStockCodes();
        Map<String, BigDecimal> currentPrices = rankingPriceCollectionService.batchFetchCurrentPrices(requiredStockCodes);
        log.info("💰 현재가 수집 완료: {}개", currentPrices.size());

        // 2. 총자산 기준 랭킹 생성
        return getMainRankingsWithPrices(currentPrices);
    }

    // ==================== 대회 계좌 랭킹 ====================

    /**
     * 특정 대회 전체 랭킹 조회 (총자산 기준)
     * - 잔액순 (sortBy = "balance") → 총자산 기준
     * - 수익률순 (sortBy = "returnRate") → 총자산 기준 수익률
     * - 로컬 캐시 사용 (70초 TTL)
     *
     * @param contestId 대회 ID
     * @param sortBy    정렬 기준 ("balance" | "returnRate")
     * @return RankingResponse
     */
    @Cacheable(value = "rankings", key = "'contest:' + #contestId + ':' + #sortBy")
    // 사유는 getMainRankings 상단 주석 참조 (REQUIRES_NEW로 배치 트랜잭션과 격리)
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RankingResponse getContestRankings(Long contestId, String sortBy) {
        log.info("대회 [{}] 랭킹 조회 (sortBy: {}) - 총자산 기준 DB 로드", contestId, sortBy);

        // (정규화 데드라인 제거됨: 응답 sortBy는 getContestRankingsWithPrices의 isReturnRate 삼항으로 독립 계산됨 — 버그 g)

        // 1. 모든 보유 종목의 현재가 배치 수집
        Set<String> requiredStockCodes = rankingPriceCollectionService.collectAllHeldStockCodes();
        Map<String, BigDecimal> currentPrices = rankingPriceCollectionService.batchFetchCurrentPrices(requiredStockCodes);
        log.info("💰 현재가 수집 완료: {}개", currentPrices.size());

        // 2. 총자산 기준 랭킹 생성
        return getContestRankingsWithPrices(contestId, sortBy, currentPrices);
    }

    // ==================== 내 랭킹 조회 ====================

    /**
     * 내 랭킹 정보 조회
     * - 캐시된 랭킹 데이터에서 내 순위를 찾음
     * - Main 계좌: 총자산 순위만 제공
     * - 대회 계좌: 총자산 순위 + 수익률 순위 제공
     *
     * @param memberId  회원 ID
     * @param contestId 대회 ID (null이면 Main 계좌)
     * @return MyRankResponse
     */
    public MyRankResponse getMyRank(Long memberId, Long contestId) {
        log.info("🔍 내 랭킹 조회 (memberId: {}, contestId: {})", memberId, contestId);

        // 1. 내 계좌 찾기
        Account myAccount = findMyAccount(memberId, contestId);

        // 2. Main 계좌인 경우 — 이미 호출하는 getMainRankings() 응답에서 내 엔트리를 파생시킨다
        // (버그 b 수정: 별도 재계산 스냅샷을 만들지 않아 캐시된 랭킹과 값이 항상 일치한다)
        if (contestId == null) {
            RankingResponse mainRankings = getMainRankings();
            RankingItemResponse myEntry = findMyEntry(mainRankings.getRankings(), memberId).orElse(null);
            Long balanceRank = myEntry != null ? Long.valueOf(myEntry.getRank()) : null;
            BigDecimal myTotalAssets = myEntry != null ? myEntry.getTotalAssets() : myAccount.getCash();

            // 티어 및 칭호 조회
            grit.stockIt.domain.member.entity.Member member = myAccount.getMember();
            String representativeTitle = member.getRepresentativeTitle() != null 
                    ? member.getRepresentativeTitle().getName() 
                    : null;
            Long representativeTitleId = member.getRepresentativeTitle() != null
                    ? member.getRepresentativeTitle().getId()
                    : null;
            String tier = getTierForMember(member);
            
            return MyRankResponse.builder()
                    .balanceRank(balanceRank)
                    .returnRateRank(null) // Main 계좌는 수익률 없음
                    .totalParticipants(mainRankings.getTotalParticipants())
                    .myBalance(myAccount.getCash())
                    .myTotalAssets(myTotalAssets)
                    .myReturnRate(null)
                    .representativeTitle(representativeTitle)
                    .representativeTitleId(representativeTitleId)
                    .tier(tier)
                    .build();
        }

        // 3. 대회 계좌인 경우 — 이미 호출하는 getContestRankings() 응답에서 내 엔트리를 파생시킨다
        // (버그 b+k 수정: totalAssets/returnRate 재계산 스냅샷을 만들지 않아 캐시된 랭킹과 값이 항상 일치한다)
        contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("대회를 찾을 수 없습니다. (ID: " + contestId + ")"));

        RankingResponse balanceRankings = getContestRankings(contestId, "totalAssets");
        RankingResponse returnRateRankings = getContestRankings(contestId, "returnRate");

        RankingItemResponse balanceEntry = findMyEntry(balanceRankings.getRankings(), memberId).orElse(null);
        RankingItemResponse returnRateEntry = findMyEntry(returnRateRankings.getRankings(), memberId).orElse(null);

        Long balanceRank = balanceEntry != null ? Long.valueOf(balanceEntry.getRank()) : null;
        Long returnRateRank = returnRateEntry != null ? Long.valueOf(returnRateEntry.getRank()) : null;
        BigDecimal myTotalAssets = balanceEntry != null ? balanceEntry.getTotalAssets() : myAccount.getCash();
        BigDecimal myReturnRate = returnRateEntry != null ? returnRateEntry.getReturnRate() : null;

        // 티어 및 칭호 조회
        grit.stockIt.domain.member.entity.Member member = myAccount.getMember();
        String representativeTitle = member.getRepresentativeTitle() != null 
                ? member.getRepresentativeTitle().getName() 
                : null;
        Long representativeTitleId = member.getRepresentativeTitle() != null
                ? member.getRepresentativeTitle().getId()
                : null;
        String tier = getTierForMember(member);

        return MyRankResponse.builder()
                .balanceRank(balanceRank)
                .returnRateRank(returnRateRank)
                .totalParticipants(balanceRankings.getTotalParticipants())
                .myBalance(myAccount.getCash())
                .myTotalAssets(myTotalAssets)
                .myReturnRate(myReturnRate)
                .representativeTitle(representativeTitle)
                .representativeTitleId(representativeTitleId)
                .tier(tier)
                .build();
    }

    /**
     * 랭킹 리스트에서 특정 회원의 엔트리 찾기
     */
    private Optional<RankingItemResponse> findMyEntry(List<RankingItemResponse> rankings, Long memberId) {
        return rankings.stream()
                .filter(dto -> dto.getMemberId().equals(memberId))
                .findFirst();
    }

    /**
     * 회원의 티어 정보 조회
     */
    private String getTierForMember(grit.stockIt.domain.member.entity.Member member) {
        try {
            UserTierStatusResponse tierInfo = missionQueryService.getTierInfo(member.getEmail());
            return tierInfo.getCurrentTier();
        } catch (Exception e) {
            log.warn("티어 조회 실패 (memberId: {}): {}", member.getMemberId(), e.getMessage());
            return null;
        }
    }

    // ==================== Private 헬퍼 메서드 ====================

    /**
     * 내 계좌 찾기
     *
     * @param memberId  회원 ID
     * @param contestId 대회 ID (null이면 Main 계좌)
     * @return Account
     */
    private Account findMyAccount(Long memberId, Long contestId) {
        if (contestId == null) {
            // Main 계좌 조회 (DB 레벨에서 필터링)
            return accountRepository.findByMemberIdAndIsDefaultTrue(memberId)
                    .orElseThrow(() -> new IllegalArgumentException("Main 계좌를 찾을 수 없습니다."));
        } else {
            // 대회 계좌 조회 (DB 레벨에서 필터링)
            return accountRepository.findByMemberIdAndContestId(memberId, contestId)
                    .orElseThrow(() -> new IllegalArgumentException("대회 계좌를 찾을 수 없습니다."));
        }
    }
    // ==================== 헬퍼 클래스 ====================

    /**
     * 계좌 + 총자산 wrapper 클래스
     */
    private static class AccountWithAssets {
        final Account account;
        final BigDecimal totalAssets;

        AccountWithAssets(Account account, BigDecimal totalAssets) {
            this.account = account;
            this.totalAssets = totalAssets;
        }
    }

    /**
     * AccountWithAssets 리스트를 RankingItemResponse 리스트로 변환 (총자산 기준)
     */
    private List<RankingItemResponse> convertToRankingItemResponsesWithAssets(List<AccountWithAssets> accountsWithAssets) {
        List<RankingItemResponse> rankings = new ArrayList<>();
        List<BigDecimal> sortedValues = accountsWithAssets.stream()
                .map(awa -> awa.totalAssets)
                .collect(Collectors.toList());
        List<Integer> ranks = rankingCalculationService.assignCompetitionRanks(sortedValues);

        for (int i = 0; i < accountsWithAssets.size(); i++) {
            AccountWithAssets awa = accountsWithAssets.get(i);
            Account account = awa.account;
            BigDecimal currentValue = awa.totalAssets;
            int rank = ranks.get(i);

            // returnRate는 총자산순 변환에서는 항상 null (수익률순은 ForReturnRate 변환기가 별도 처리)
            BigDecimal returnRate = null;

            // 칭호와 티어 정보 조회
            String representativeTitle = account.getMember().getRepresentativeTitle() != null 
                    ? account.getMember().getRepresentativeTitle().getName() 
                    : null;
            Long representativeTitleId = account.getMember().getRepresentativeTitle() != null
                    ? account.getMember().getRepresentativeTitle().getId()
                    : null;
            String tier = getTierForMember(account.getMember());

            RankingItemResponse dto = RankingItemResponse.builder()
                    .rank(rank)
                    .memberId(account.getMember().getMemberId())
                    .nickname(account.getMember().getName())
                    .profileImage(account.getMember().getProfileImage())
                    .representativeTitle(representativeTitle)
                    .representativeTitleId(representativeTitleId)
                    .tier(tier)
                    .balance(account.getCash())  // 실제 잔액 (현금만)
                    .totalAssets(currentValue)   // 총자산 (잔액 + 주식)
                    .returnRate(returnRate)
                    .build();

            rankings.add(dto);
        }

        return rankings;
    }

    /**
     * AccountWithAssets 리스트를 RankingItemResponse 리스트로 변환 (수익률 기준)
     * - totalAssets에는 실제 총자산, returnRate에는 수익률 표시
     */
    private List<RankingItemResponse> convertToRankingItemResponsesWithAssetsForReturnRate(
            List<AccountWithAssets> accountsWithAssets, Contest contest, Map<String, BigDecimal> currentPrices,
            Map<Account, List<AccountStock>> accountStocksMap) {
        
        List<RankingItemResponse> rankings = new ArrayList<>();
        List<BigDecimal> sortedValues = accountsWithAssets.stream()
                .map(awa -> awa.totalAssets)
                .collect(Collectors.toList());
        List<Integer> ranks = rankingCalculationService.assignCompetitionRanks(sortedValues);

        for (int i = 0; i < accountsWithAssets.size(); i++) {
            AccountWithAssets awa = accountsWithAssets.get(i);
            Account account = awa.account;
            BigDecimal returnRateValue = awa.totalAssets;  // totalAssets에 수익률이 들어있음
            int rank = ranks.get(i);

            // 실제 총자산 계산 (currentPrices가 비어도 항상 재계산 — 빈 맵도 취득원가 폴백 적용, 버그 h 수정)
            BigDecimal actualTotalAssets =
                    rankingCalculationService.calculateTotalAssets(account, currentPrices, accountStocksMap);

            // 칭호와 티어 정보 조회
            String representativeTitle = account.getMember().getRepresentativeTitle() != null 
                    ? account.getMember().getRepresentativeTitle().getName() 
                    : null;
            Long representativeTitleId = account.getMember().getRepresentativeTitle() != null
                    ? account.getMember().getRepresentativeTitle().getId()
                    : null;
            String tier = getTierForMember(account.getMember());

            RankingItemResponse dto = RankingItemResponse.builder()
                    .rank(rank)
                    .memberId(account.getMember().getMemberId())
                    .nickname(account.getMember().getName())
                    .profileImage(account.getMember().getProfileImage())
                    .representativeTitle(representativeTitle)
                    .representativeTitleId(representativeTitleId)
                    .tier(tier)
                    .balance(account.getCash())       // 실제 잔액 (현금만)
                    .totalAssets(actualTotalAssets)   // 총자산 (잔액 + 주식)
                    .returnRate(returnRateValue)
                    .build();

            rankings.add(dto);
        }

        return rankings;
    }

    /**
     * Main 계좌 전체 랭킹 조회 (총자산순) - 내부용 (현재가 포함)
     */
    private RankingResponse getMainRankingsWithPrices(Map<String, BigDecimal> currentPrices) {
        log.info("Main 계좌 랭킹 조회 (총자산 기준 - DB에서 로드)");

        // 1. DB에서 Main 계좌 전체 조회
        List<Account> accounts = accountRepository.findMainAccountsOrderByBalance();

        // 2. 모든 계좌의 AccountStock을 한 번에 조회 (N+1 해결)
        List<AccountStock> allAccountStocks = accountStockRepository.findAll();
        Map<Account, List<AccountStock>> accountStocksMap = allAccountStocks.stream()
                .collect(Collectors.groupingBy(AccountStock::getAccount));

        // 3. 각 계좌의 총자산 계산
        List<AccountWithAssets> accountsWithAssets = accounts.stream()
                .map(account -> {
                    BigDecimal totalAssets =
                            rankingCalculationService.calculateTotalAssets(account, currentPrices, accountStocksMap);
                    return new AccountWithAssets(account, totalAssets);
                })
                .sorted((a, b) -> b.totalAssets.compareTo(a.totalAssets)) // 총자산 내림차순
                .collect(Collectors.toList());

        // 4. Account → RankingItemResponse 변환 (순위 부여)
        List<RankingItemResponse> rankings = convertToRankingItemResponsesWithAssets(accountsWithAssets);

        // 5. 전체 인원 수
        Long totalParticipants = accountRepository.countMainAccounts();

        // 5. 응답 생성
        return RankingResponse.builder()
                .contestId(null) // Main 계좌는 contestId 없음
                .contestName("Main 계좌")
                .sortBy("totalAssets")
                .rankings(rankings)
                .totalParticipants(totalParticipants)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /**
     * 특정 대회 전체 랭킹 조회 - 내부용 (현재가 포함)
     */
    private RankingResponse getContestRankingsWithPrices(Long contestId, String sortBy, Map<String, BigDecimal> currentPrices) {
        log.info("대회 [{}] 랭킹 조회 (sortBy: {}) - 총자산 기준 DB 로드", contestId, sortBy);

        // 1. 대회 존재 여부 확인
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("대회를 찾을 수 없습니다. (ID: " + contestId + ")"));

        // 2. 대회의 모든 계좌 조회
        List<Account> accounts = accountRepository.findByContest(contest);

        // 3. 모든 계좌의 AccountStock을 한 번에 조회 (N+1 해결)
        List<AccountStock> allAccountStocks = accountStockRepository.findAll();
        Map<Account, List<AccountStock>> accountStocksMap = allAccountStocks.stream()
                .collect(Collectors.groupingBy(AccountStock::getAccount));

        // 4. sortBy에 따라 처리
        boolean isReturnRate = "returnRate".equalsIgnoreCase(sortBy);

        List<AccountWithAssets> accountsWithAssets;

        if (isReturnRate) {
            // 수익률순: 수익률 계산 후 정렬
            accountsWithAssets = accounts.stream()
                    .map(account -> {
                        BigDecimal totalAssets =
                                rankingCalculationService.calculateTotalAssets(account, currentPrices, accountStocksMap);
                        BigDecimal returnRate = rankingCalculationService.calculateReturnRateFromAssets(totalAssets, contest);
                        return new AccountWithAssets(account, returnRate);  // returnRate로 정렬
                    })
                    .sorted((a, b) -> b.totalAssets.compareTo(a.totalAssets))
                    .collect(Collectors.toList());
        } else {
            // 잔액(총자산)순
            accountsWithAssets = accounts.stream()
                    .map(account -> {
                        BigDecimal totalAssets =
                                rankingCalculationService.calculateTotalAssets(account, currentPrices, accountStocksMap);
                        return new AccountWithAssets(account, totalAssets);
                    })
                    .sorted((a, b) -> b.totalAssets.compareTo(a.totalAssets))
                    .collect(Collectors.toList());
        }

        // 4. Account → RankingItemResponse 변환
        List<RankingItemResponse> rankings = isReturnRate
                ? convertToRankingItemResponsesWithAssetsForReturnRate(accountsWithAssets, contest, currentPrices, accountStocksMap)
                : convertToRankingItemResponsesWithAssets(accountsWithAssets);

        // 5. 전체 인원 수
        Long totalParticipants = accountRepository.countByContest_ContestId(contestId);

        // 6. 응답 생성
        return RankingResponse.builder()
                .contestId(contestId)
                .contestName(contest.getContestName())
                .sortBy(isReturnRate ? "returnRate" : "totalAssets")
                .rankings(rankings)
                .totalParticipants(totalParticipants)
                .lastUpdated(LocalDateTime.now())
                .build();
    }
}

