package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.mission.service.MissionService;
import grit.stockIt.domain.mission.dto.UserTierStatusDto;
import grit.stockIt.domain.ranking.dto.MyRankDto;
import grit.stockIt.domain.ranking.dto.RankingDto;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import grit.stockIt.domain.stock.service.StockDetailService;
import com.google.common.util.concurrent.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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
    private final MissionService missionService;
    private final RedisMarketDataRepository redisMarketDataRepository;
    private final StockDetailService stockDetailService;
    
    // Rate Limiter: KIS API 초당 30개 제한 (안전하게 25개로 설정)
    private final RateLimiter kisApiRateLimiter = RateLimiter.create(25.0);
    // ==================== 스케줄러 ====================

    /**
     * 1분마다 모든 랭킹 자동 갱신
     * - Main 계좌 랭킹 갱신
     * - 진행 중인 모든 대회 랭킹 갱신
     * - 캐시 초기화 후 재생성
     */
    @Scheduled(fixedRate = 60000) // 60초 = 1분
    @CacheEvict(value = "rankings", allEntries = true)
    @Transactional
    public void updateAllRankings() {
        log.info("🔄 [스케줄러] 랭킹 갱신 시작: {}", LocalDateTime.now());

        try {
            // 0. 모든 보유 종목의 현재가 배치 수집
            Set<String> requiredStockCodes = collectAllHeldStockCodes();
            log.info("📦 전체 보유 종목 수: {}개", requiredStockCodes.size());
            
            Map<String, BigDecimal> currentPrices = batchFetchCurrentPrices(requiredStockCodes);
            log.info("💰 현재가 수집 완료: {}개 (캐시 히트 포함)", currentPrices.size());

            // 1. Main 계좌 랭킹 갱신 (총자산 기준)
            RankingResponse mainRanking = getMainRankingsWithPrices(currentPrices);
            log.info("Main 계좌 랭킹 갱신 완료");

            // --- [추가] Main 랭킹 Top 10 유저에게 '랭커' 칭호 지급 로직 ---
            if (mainRanking != null && mainRanking.getRankings() != null) {
                List<Long> top10MemberIds = mainRanking.getRankings().stream()
                        .filter(dto -> dto.getRank() <= 10) // 1위~10위 필터링
                        .map(RankingDto::getMemberId)       // MemberId 추출
                        .collect(Collectors.toList());

                // MissionService로 Top 10 명단 전달 (미션 달성 처리)
                if (!top10MemberIds.isEmpty()) {
                    missionService.processRankerAchievement(top10MemberIds);
                }
            }
            // 2. 진행 중인 대회 랭킹 갱신
            List<Contest> activeContests = contestRepository.findActiveContests(LocalDateTime.now());
            log.info("진행 중인 대회 수: {}", activeContests.size());

            for (Contest contest : activeContests) {
                // 총자산순 랭킹
                getContestRankingsWithPrices(contest.getContestId(), "totalAssets", currentPrices);
                // 수익률순 랭킹
                getContestRankingsWithPrices(contest.getContestId(), "returnRate", currentPrices);
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
    public RankingResponse getMainRankings() {
        log.info("Main 계좌 랭킹 조회 (총자산 기준 - DB에서 로드)");

        // 1. 모든 보유 종목의 현재가 배치 수집
        Set<String> requiredStockCodes = collectAllHeldStockCodes();
        Map<String, BigDecimal> currentPrices = batchFetchCurrentPrices(requiredStockCodes);
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
    public RankingResponse getContestRankings(Long contestId, String sortBy) {
        log.info("대회 [{}] 랭킹 조회 (sortBy: {}) - 총자산 기준 DB 로드", contestId, sortBy);

        // sortBy 정규화: balance → totalAssets (하위 호환성)
        if ("balance".equalsIgnoreCase(sortBy)) {
            sortBy = "totalAssets";
        }

        // 1. 모든 보유 종목의 현재가 배치 수집
        Set<String> requiredStockCodes = collectAllHeldStockCodes();
        Map<String, BigDecimal> currentPrices = batchFetchCurrentPrices(requiredStockCodes);
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
     * @return MyRankDto
     */
    public MyRankDto getMyRank(Long memberId, Long contestId) {
        log.info("🔍 내 랭킹 조회 (memberId: {}, contestId: {})", memberId, contestId);

        // 1. 내 계좌 찾기
        Account myAccount = findMyAccount(memberId, contestId);

        // 2. 현재가 수집 및 총자산 계산
        Set<String> requiredStockCodes = collectAllHeldStockCodes();
        Map<String, BigDecimal> currentPrices = batchFetchCurrentPrices(requiredStockCodes);
        
        // AccountStock Map 조회
        List<AccountStock> allAccountStocks = accountStockRepository.findAllByAccount(myAccount);
        Map<Account, List<AccountStock>> accountStocksMap = Map.of(myAccount, allAccountStocks);
        
        BigDecimal myTotalAssets = calculateTotalAssets(myAccount, currentPrices, accountStocksMap);

        // 3. Main 계좌인 경우
        if (contestId == null) {
            // 캐시된 Main 랭킹에서 내 순위 찾기
            RankingResponse mainRankings = getMainRankings();
            Long balanceRank = findMyRankInList(mainRankings.getRankings(), memberId);
            
            // 티어 및 칭호 조회
            grit.stockIt.domain.member.entity.Member member = myAccount.getMember();
            String representativeTitle = member.getRepresentativeTitle() != null 
                    ? member.getRepresentativeTitle().getName() 
                    : null;
            Long representativeTitleId = member.getRepresentativeTitle() != null
                    ? member.getRepresentativeTitle().getId()
                    : null;
            String tier = getTierForMember(member);
            
            return MyRankDto.builder()
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

        // 4. 대회 계좌인 경우
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("대회를 찾을 수 없습니다. (ID: " + contestId + ")"));

        // 4-1. 내 수익률 계산 (총자산 기준)
        BigDecimal myReturnRate = calculateReturnRateFromAssets(myTotalAssets, contest);

        // 4-2. 캐시된 대회 랭킹에서 내 순위 찾기
        RankingResponse balanceRankings = getContestRankings(contestId, "totalAssets");
        RankingResponse returnRateRankings = getContestRankings(contestId, "returnRate");
        
        Long balanceRank = findMyRankInList(balanceRankings.getRankings(), memberId);
        Long returnRateRank = findMyRankInList(returnRateRankings.getRankings(), memberId);

        // 티어 및 칭호 조회
        grit.stockIt.domain.member.entity.Member member = myAccount.getMember();
        String representativeTitle = member.getRepresentativeTitle() != null 
                ? member.getRepresentativeTitle().getName() 
                : null;
        Long representativeTitleId = member.getRepresentativeTitle() != null
                ? member.getRepresentativeTitle().getId()
                : null;
        String tier = getTierForMember(member);

        return MyRankDto.builder()
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
     * 랭킹 리스트에서 특정 회원의 순위 찾기
     */
    private Long findMyRankInList(List<RankingDto> rankings, Long memberId) {
        return rankings.stream()
                .filter(dto -> dto.getMemberId().equals(memberId))
                .map(RankingDto::getRank)
                .findFirst()
                .map(Integer::longValue)
                .orElse(null);
    }

    /**
     * 회원의 티어 정보 조회
     */
    private String getTierForMember(grit.stockIt.domain.member.entity.Member member) {
        try {
            UserTierStatusDto tierInfo = missionService.getTierInfo(member.getEmail());
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

    /**
     * 수익률 계산 (레거시 - getMyRank에서만 사용)
     * - 수익률 = (현재잔액 - 시드머니) / 시드머니 * 100
     * - 소수점 2자리까지 표시
     *
     * @param account 계좌
     * @param contest 대회
     * @return 수익률 (%)
     */
    private BigDecimal calculateReturnRate(Account account, Contest contest) {
        if (contest == null || contest.getSeedMoney() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal seedMoney = BigDecimal.valueOf(contest.getSeedMoney());
        if (seedMoney.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO; // 0으로 나누기 방지
        }

        // (현재잔액 - 시드머니) / 시드머니 * 100
        BigDecimal profit = account.getCash().subtract(seedMoney);
        BigDecimal returnRate = profit.divide(seedMoney, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        // 소수점 2자리까지 반올림
        return returnRate.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 총자산으로부터 수익률 계산
     */
    private BigDecimal calculateReturnRateFromAssets(BigDecimal totalAssets, Contest contest) {
        if (contest == null || contest.getSeedMoney() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal seedMoney = BigDecimal.valueOf(contest.getSeedMoney());
        if (seedMoney.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // (총자산 - 시드머니) / 시드머니 * 100
        BigDecimal profit = totalAssets.subtract(seedMoney);
        BigDecimal returnRate = profit.divide(seedMoney, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return returnRate.setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== 배치 현재가 수집 ====================

    /**
     * 모든 계좌의 보유 종목 코드 수집 (중복 제거)
     * JPQL로 DISTINCT 조회하여 DB 레벨에서 중복 제거
     */
    private Set<String> collectAllHeldStockCodes() {
        List<String> stockCodes = accountStockRepository.findDistinctStockCodes();
        return new HashSet<>(stockCodes);
    }

    /**
     * 배치로 종목 현재가 수집
     * 1. Redis 캐시 우선 조회
     * 2. 캐시 미스 시 KIS API 호출 (Rate Limiting 적용)
     * 
     * @param stockCodes 조회할 종목 코드 Set
     * @return Map<종목코드, 현재가>
     */
    private Map<String, BigDecimal> batchFetchCurrentPrices(Set<String> stockCodes) {
        Map<String, BigDecimal> prices = new HashMap<>();
        List<String> cacheMissStocks = new ArrayList<>();

        // 1단계: Redis 캐시에서 조회
        for (String stockCode : stockCodes) {
            Optional<BigDecimal> cachedPrice = redisMarketDataRepository.getLastPrice(stockCode);
            if (cachedPrice.isPresent()) {
                prices.put(stockCode, cachedPrice.get());
            } else {
                cacheMissStocks.add(stockCode);
            }
        }

        log.info("캐시 히트: {}/{} (미스: {}개)", 
                prices.size(), stockCodes.size(), cacheMissStocks.size());

        // 2단계: 캐시 미스 종목만 KIS API 호출 (Rate Limiting)
        if (!cacheMissStocks.isEmpty()) {
            fetchPricesWithRateLimit(cacheMissStocks, prices);
        }

        return prices;
    }

    /**
     * Rate Limiting을 적용하여 KIS API에서 현재가 조회
     * - 초당 25개로 제한
     * - 실패 시 로그만 기록하고 계속 진행 (해당 종목은 0원 처리)
     */
    private void fetchPricesWithRateLimit(List<String> stockCodes, Map<String, BigDecimal> prices) {
        int successCount = 0;
        int failCount = 0;

        for (String stockCode : stockCodes) {
            try {
                // Rate Limiter 적용 (초당 25개 제한)
                kisApiRateLimiter.acquire();

                // StockDetailService의 getCurrentPrice 호출 (비동기 → 동기 변환)
                BigDecimal price = stockDetailService.getCurrentPrice(stockCode)
                        .timeout(Duration.ofSeconds(3))
                        .block();

                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    prices.put(stockCode, price);
                    successCount++;
                } else {
                    log.warn("종목 {} 현재가 조회 실패 (null 또는 0원)", stockCode);
                    prices.put(stockCode, BigDecimal.ZERO);
                    failCount++;
                }

            } catch (Exception e) {
                log.error("종목 {} 현재가 조회 중 예외 발생: {}", stockCode, e.getMessage());
                prices.put(stockCode, BigDecimal.ZERO);
                failCount++;
            }
        }

        log.info("🔄 KIS API 호출 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }

    /**
     * 계좌의 총자산 계산
     * 총자산 = 잔액 + Σ(보유수량 × 현재가)
     * 
     * @param account       계좌
     * @param currentPrices 종목코드별 현재가 Map
     * @param accountStocksMap Account별 AccountStock 리스트 Map
     * @return 총자산
     */
    private BigDecimal calculateTotalAssets(Account account, Map<String, BigDecimal> currentPrices, 
                                           Map<Account, List<AccountStock>> accountStocksMap) {
        BigDecimal cash = account.getCash();
        BigDecimal stockValue = BigDecimal.ZERO;

        List<AccountStock> holdings = accountStocksMap.getOrDefault(account, Collections.emptyList());
        for (AccountStock holding : holdings) {
            if (holding.getQuantity() <= 0) {
                continue;
            }

            String stockCode = holding.getStock().getCode();
            BigDecimal currentPrice = currentPrices.getOrDefault(stockCode, BigDecimal.ZERO);
            BigDecimal value = currentPrice.multiply(BigDecimal.valueOf(holding.getQuantity()));
            stockValue = stockValue.add(value);
        }

        return cash.add(stockValue);
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
     * AccountWithAssets 리스트를 RankingDto 리스트로 변환 (총자산 기준)
     */
    private List<RankingDto> convertToRankingDtosWithAssets(List<AccountWithAssets> accountsWithAssets, boolean includeReturn) {
        List<RankingDto> rankings = new ArrayList<>();
        int rank = 1;
        BigDecimal prevValue = null;
        int sameRankCount = 0;

        for (AccountWithAssets awa : accountsWithAssets) {
            Account account = awa.account;
            BigDecimal currentValue = awa.totalAssets;

            // 동률 처리: 이전 값과 같으면 같은 순위
            if (prevValue != null && prevValue.compareTo(currentValue) == 0) {
                sameRankCount++;
            } else {
                rank += sameRankCount;
                sameRankCount = 1;
            }

            BigDecimal returnRate = null;
            if (includeReturn && account.getContest() != null) {
                returnRate = calculateReturnRate(account, account.getContest());
            }

            // 칭호와 티어 정보 조회
            String representativeTitle = account.getMember().getRepresentativeTitle() != null 
                    ? account.getMember().getRepresentativeTitle().getName() 
                    : null;
            Long representativeTitleId = account.getMember().getRepresentativeTitle() != null
                    ? account.getMember().getRepresentativeTitle().getId()
                    : null;
            String tier = getTierForMember(account.getMember());

            RankingDto dto = RankingDto.builder()
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
            prevValue = currentValue;
        }

        return rankings;
    }

    /**
     * AccountWithAssets 리스트를 RankingDto 리스트로 변환 (수익률 기준)
     * - totalAssets에는 실제 총자산, returnRate에는 수익률 표시
     */
    private List<RankingDto> convertToRankingDtosWithAssetsForReturnRate(
            List<AccountWithAssets> accountsWithAssets, Contest contest, Map<String, BigDecimal> currentPrices,
            Map<Account, List<AccountStock>> accountStocksMap) {
        
        List<RankingDto> rankings = new ArrayList<>();
        int rank = 1;
        BigDecimal prevReturnRate = null;
        int sameRankCount = 0;

        for (AccountWithAssets awa : accountsWithAssets) {
            Account account = awa.account;
            BigDecimal returnRateValue = awa.totalAssets;  // totalAssets에 수익률이 들어있음

            // 동률 처리
            if (prevReturnRate != null && prevReturnRate.compareTo(returnRateValue) == 0) {
                sameRankCount++;
            } else {
                rank += sameRankCount;
                sameRankCount = 1;
            }

            // 실제 총자산 계산
            BigDecimal actualTotalAssets = currentPrices.isEmpty()
                    ? account.getCash()
                    : calculateTotalAssets(account, currentPrices, accountStocksMap);

            // 칭호와 티어 정보 조회
            String representativeTitle = account.getMember().getRepresentativeTitle() != null 
                    ? account.getMember().getRepresentativeTitle().getName() 
                    : null;
            Long representativeTitleId = account.getMember().getRepresentativeTitle() != null
                    ? account.getMember().getRepresentativeTitle().getId()
                    : null;
            String tier = getTierForMember(account.getMember());

            RankingDto dto = RankingDto.builder()
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
            prevReturnRate = returnRateValue;
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
                    BigDecimal totalAssets = currentPrices.isEmpty() 
                            ? account.getCash()  // 현재가 없으면 잔액만 사용 (레거시)
                            : calculateTotalAssets(account, currentPrices, accountStocksMap);
                    return new AccountWithAssets(account, totalAssets);
                })
                .sorted((a, b) -> b.totalAssets.compareTo(a.totalAssets)) // 총자산 내림차순
                .collect(Collectors.toList());

        // 4. Account → RankingDto 변환 (순위 부여)
        List<RankingDto> rankings = convertToRankingDtosWithAssets(accountsWithAssets, false);

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
                        BigDecimal totalAssets = currentPrices.isEmpty()
                                ? account.getCash()
                                : calculateTotalAssets(account, currentPrices, accountStocksMap);
                        BigDecimal returnRate = calculateReturnRateFromAssets(totalAssets, contest);
                        return new AccountWithAssets(account, returnRate);  // returnRate로 정렬
                    })
                    .sorted((a, b) -> b.totalAssets.compareTo(a.totalAssets))
                    .collect(Collectors.toList());
        } else {
            // 잔액(총자산)순
            accountsWithAssets = accounts.stream()
                    .map(account -> {
                        BigDecimal totalAssets = currentPrices.isEmpty()
                                ? account.getCash()
                                : calculateTotalAssets(account, currentPrices, accountStocksMap);
                        return new AccountWithAssets(account, totalAssets);
                    })
                    .sorted((a, b) -> b.totalAssets.compareTo(a.totalAssets))
                    .collect(Collectors.toList());
        }

        // 4. Account → RankingDto 변환
        List<RankingDto> rankings = isReturnRate
                ? convertToRankingDtosWithAssetsForReturnRate(accountsWithAssets, contest, currentPrices, accountStocksMap)
                : convertToRankingDtosWithAssets(accountsWithAssets, false);

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

