package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.ranking.dto.PerformanceResult;
import grit.stockIt.domain.ranking.dto.RankingDto;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 성능 비교 테스트 전용 서비스
 * - 캐시 O vs 캐시 X 성능 비교
 * - 테스트 완료 후 이 파일 전체 삭제 예정
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceTestService {

    private final AccountRepository accountRepository;
    private final ContestRepository contestRepository;
    private final RankingService rankingService; // 캐시 있는 메서드 호출용

    // ==================== 캐시 없는 랭킹 조회 ====================

    /**
     * Main 계좌 전체 랭킹 조회 (캐시 없음)
     * - @Cacheable 없이 매번 DB 조회
     * - 성능 비교 테스트 전용
     *
     * @return RankingResponse
     */
    public RankingResponse getMainRankingsNoCache() {
        log.info("📊 Main 계좌 랭킹 조회 (캐시 없음 - 매번 DB 조회)");

        // 1. DB에서 Main 계좌 전체 조회 (잔액 내림차순)
        List<Account> accounts = accountRepository.findMainAccountsOrderByBalance();

        // 2. Account → RankingDto 변환 (순위 부여)
        List<RankingDto> rankings = convertToRankingDtos(accounts, false);

        // 3. 전체 인원 수
        Long totalParticipants = accountRepository.countMainAccounts();

        // 4. 응답 생성
        return RankingResponse.builder()
                .contestId(null)
                .contestName("Main 계좌 (캐시 없음)")
                .sortBy("balance")
                .rankings(rankings)
                .totalParticipants(totalParticipants)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    /**
     * 대회 계좌 전체 랭킹 조회 (캐시 없음)
     * - 성능 비교 테스트 전용
     *
     * @param contestId 대회 ID
     * @param sortBy    정렬 기준 ("balance" | "returnRate")
     * @return RankingResponse
     */
    public RankingResponse getContestRankingsNoCache(Long contestId, String sortBy) {
        log.info("📊 대회 [{}] 랭킹 조회 (캐시 없음 - 매번 DB 조회)", contestId);

        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("대회를 찾을 수 없습니다. (ID: " + contestId + ")"));

        List<Account> accounts;
        boolean isReturnRate = "returnRate".equalsIgnoreCase(sortBy);

        if (isReturnRate) {
            accounts = accountRepository.findByContestIdOrderByReturnRate(contestId);
        } else {
            accounts = accountRepository.findByContestIdOrderByBalance(contestId);
        }

        List<RankingDto> rankings = convertToRankingDtos(accounts, isReturnRate);
        Long totalParticipants = accountRepository.countByContest_ContestId(contestId);

        return RankingResponse.builder()
                .contestId(contestId)
                .contestName(contest.getContestName() + " (캐시 없음)")
                .sortBy(isReturnRate ? "returnRate" : "balance")
                .rankings(rankings)
                .totalParticipants(totalParticipants)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 성능 비교 테스트 ====================

    /**
     * 캐시 O vs 캐시 X 성능 비교
     * - requestCount만큼 반복 요청하여 성능 측정
     * - Main 계좌 랭킹 기준
     *
     * @param requestCount 요청 횟수 (예: 100)
     * @return PerformanceResult
     */
    public PerformanceResult compareMainRankingPerformance(int requestCount) {
        log.info("🚀 [성능 비교] 시작 - {} 회 요청 (Main 계좌)", requestCount);

        // 1. 캐시 사용 O 성능 측정
        long cachedTotalTime = 0;
        long cachedMinTime = Long.MAX_VALUE;
        long cachedMaxTime = 0;

        for (int i = 0; i < requestCount; i++) {
            long startTime = System.currentTimeMillis();
            rankingService.getMainRankings(); // 캐시 사용
            long endTime = System.currentTimeMillis();

            long duration = endTime - startTime;
            cachedTotalTime += duration;
            cachedMinTime = Math.min(cachedMinTime, duration);
            cachedMaxTime = Math.max(cachedMaxTime, duration);
        }

        double cachedAvgTime = (double) cachedTotalTime / requestCount;
        log.info("✅ [캐시 O] 평균: {}ms, 최소: {}ms, 최대: {}ms, 총: {}ms",
                String.format("%.2f", cachedAvgTime), cachedMinTime, cachedMaxTime, cachedTotalTime);

        // 2. 캐시 사용 X 성능 측정
        long noCacheTotalTime = 0;
        long noCacheMinTime = Long.MAX_VALUE;
        long noCacheMaxTime = 0;

        for (int i = 0; i < requestCount; i++) {
            long startTime = System.currentTimeMillis();
            getMainRankingsNoCache(); // 캐시 없음
            long endTime = System.currentTimeMillis();

            long duration = endTime - startTime;
            noCacheTotalTime += duration;
            noCacheMinTime = Math.min(noCacheMinTime, duration);
            noCacheMaxTime = Math.max(noCacheMaxTime, duration);
        }

        double noCacheAvgTime = (double) noCacheTotalTime / requestCount;
        log.info("✅ [캐시 X] 평균: {}ms, 최소: {}ms, 최대: {}ms, 총: {}ms",
                String.format("%.2f", noCacheAvgTime), noCacheMinTime, noCacheMaxTime, noCacheTotalTime);

        // 3. 성능 비교 계산
        double improvementPercent = ((noCacheAvgTime - cachedAvgTime) / noCacheAvgTime) * 100;
        double speedupFactor = noCacheAvgTime / cachedAvgTime;
        double dbLoadReduction = ((requestCount - 1.0) / requestCount) * 100; // 캐시는 첫 번째만 DB 조회

        String winner = cachedAvgTime < noCacheAvgTime ? "캐시 사용 O" : "캐시 사용 X";
        String conclusion = String.format(
                "캐시 사용 시 %.1f배 빠르며, DB 쿼리는 %.1f%% 감소했습니다. (평균 %.2fms → %.2fms)",
                speedupFactor, dbLoadReduction, noCacheAvgTime, cachedAvgTime
        );

        log.info("🎉 [성능 비교] 완료 - {}", conclusion);

        // 4. 결과 반환
        return PerformanceResult.builder()
                .requestCount(requestCount)
                .cachedAvgTimeMs(cachedAvgTime)
                .noCacheAvgTimeMs(noCacheAvgTime)
                .cachedMinTimeMs(cachedMinTime)
                .cachedMaxTimeMs(cachedMaxTime)
                .noCacheMinTimeMs(noCacheMinTime)
                .noCacheMaxTimeMs(noCacheMaxTime)
                .cachedTotalTimeMs(cachedTotalTime)
                .noCacheTotalTimeMs(noCacheTotalTime)
                .cachedDbQueryCount(1) // 캐시는 첫 번째만 DB 조회
                .noCacheDbQueryCount(requestCount) // 매번 DB 조회
                .winner(winner)
                .improvementPercent(improvementPercent)
                .speedupFactor(speedupFactor)
                .dbLoadReduction(dbLoadReduction)
                .conclusion(conclusion)
                .build();
    }

    /**
     * 대회 랭킹 성능 비교
     *
     * @param contestId    대회 ID
     * @param sortBy       정렬 기준
     * @param requestCount 요청 횟수
     * @return PerformanceResult
     */
    public PerformanceResult compareContestRankingPerformance(Long contestId, String sortBy, int requestCount) {
        log.info("🚀 [성능 비교] 시작 - {} 회 요청 (대회: {}, sortBy: {})", requestCount, contestId, sortBy);

        // 1. 캐시 사용 O
        long cachedTotalTime = 0;
        long cachedMinTime = Long.MAX_VALUE;
        long cachedMaxTime = 0;

        for (int i = 0; i < requestCount; i++) {
            long startTime = System.currentTimeMillis();
            rankingService.getContestRankings(contestId, sortBy);
            long endTime = System.currentTimeMillis();

            long duration = endTime - startTime;
            cachedTotalTime += duration;
            cachedMinTime = Math.min(cachedMinTime, duration);
            cachedMaxTime = Math.max(cachedMaxTime, duration);
        }

        double cachedAvgTime = (double) cachedTotalTime / requestCount;

        // 2. 캐시 사용 X
        long noCacheTotalTime = 0;
        long noCacheMinTime = Long.MAX_VALUE;
        long noCacheMaxTime = 0;

        for (int i = 0; i < requestCount; i++) {
            long startTime = System.currentTimeMillis();
            getContestRankingsNoCache(contestId, sortBy);
            long endTime = System.currentTimeMillis();

            long duration = endTime - startTime;
            noCacheTotalTime += duration;
            noCacheMinTime = Math.min(noCacheMinTime, duration);
            noCacheMaxTime = Math.max(noCacheMaxTime, duration);
        }

        double noCacheAvgTime = (double) noCacheTotalTime / requestCount;

        // 3. 결과 계산
        double improvementPercent = ((noCacheAvgTime - cachedAvgTime) / noCacheAvgTime) * 100;
        double speedupFactor = noCacheAvgTime / cachedAvgTime;
        double dbLoadReduction = ((requestCount - 1.0) / requestCount) * 100;

        String winner = cachedAvgTime < noCacheAvgTime ? "캐시 사용 O" : "캐시 사용 X";
        String conclusion = String.format(
                "캐시 사용 시 %.1f배 빠르며, DB 쿼리는 %.1f%% 감소했습니다.",
                speedupFactor, dbLoadReduction
        );

        log.info("🎉 [성능 비교] 완료 - {}", conclusion);

        return PerformanceResult.builder()
                .requestCount(requestCount)
                .cachedAvgTimeMs(cachedAvgTime)
                .noCacheAvgTimeMs(noCacheAvgTime)
                .cachedMinTimeMs(cachedMinTime)
                .cachedMaxTimeMs(cachedMaxTime)
                .noCacheMinTimeMs(noCacheMinTime)
                .noCacheMaxTimeMs(noCacheMaxTime)
                .cachedTotalTimeMs(cachedTotalTime)
                .noCacheTotalTimeMs(noCacheTotalTime)
                .cachedDbQueryCount(1)
                .noCacheDbQueryCount(requestCount)
                .winner(winner)
                .improvementPercent(improvementPercent)
                .speedupFactor(speedupFactor)
                .dbLoadReduction(dbLoadReduction)
                .conclusion(conclusion)
                .build();
    }

    // ==================== Private 헬퍼 메서드 ====================

    /**
     * Account 리스트를 RankingDto 리스트로 변환
     */
    private List<RankingDto> convertToRankingDtos(List<Account> accounts, boolean includeReturn) {
        List<RankingDto> rankings = new ArrayList<>();
        int rank = 1;

        for (Account account : accounts) {
            BigDecimal returnRate = null;

            if (includeReturn && account.getContest() != null) {
                returnRate = calculateReturnRate(account, account.getContest());
            }

            RankingDto dto = RankingDto.builder()
                    .rank(rank++)
                    .memberId(account.getMember().getMemberId())
                    .nickname(account.getMember().getNickname())
                    .balance(account.getCash())
                    .returnRate(returnRate)
                    .build();

            rankings.add(dto);
        }

        return rankings;
    }

    /**
     * 수익률 계산
     */
    private BigDecimal calculateReturnRate(Account account, Contest contest) {
        if (contest == null || contest.getSeedMoney() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal seedMoney = contest.getSeedMoney();
        if (seedMoney.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal profit = account.getCash().subtract(seedMoney);
        BigDecimal returnRate = profit.divide(seedMoney, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return returnRate.setScale(2, RoundingMode.HALF_UP);
    }
}

