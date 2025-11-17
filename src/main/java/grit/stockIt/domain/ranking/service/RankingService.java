package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.ranking.dto.MyRankDto;
import grit.stockIt.domain.ranking.dto.RankingDto;
import grit.stockIt.domain.ranking.dto.RankingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
    private final ContestRepository contestRepository;

    // ==================== 스케줄러 ====================

    /**
     * 1분마다 모든 랭킹 자동 갱신
     * - Main 계좌 랭킹 갱신
     * - 진행 중인 모든 대회 랭킹 갱신
     * - 캐시 초기화 후 재생성
     */
    @Scheduled(fixedRate = 60000) // 60초 = 1분
    @CacheEvict(value = "rankings", allEntries = true)
    public void updateAllRankings() {
        log.info("🔄 [스케줄러] 랭킹 갱신 시작: {}", LocalDateTime.now());

        try {
            // 1. Main 계좌 랭킹 갱신 (캐시 워밍업)
            getMainRankings();
            log.info("✅ Main 계좌 랭킹 갱신 완료");

            // 2. 진행 중인 대회 랭킹 갱신
            List<Contest> activeContests = contestRepository.findActiveContests(LocalDateTime.now());
            log.info("📊 진행 중인 대회 수: {}", activeContests.size());

            for (Contest contest : activeContests) {
                // 잔액순 랭킹
                getContestRankings(contest.getContestId(), "balance");
                // 수익률순 랭킹
                getContestRankings(contest.getContestId(), "returnRate");
                log.info("✅ 대회 [{}] 랭킹 갱신 완료", contest.getContestName());
            }

            log.info("🎉 [스케줄러] 모든 랭킹 갱신 완료: {}", LocalDateTime.now());

        } catch (Exception e) {
            log.error("❌ [스케줄러] 랭킹 갱신 중 오류 발생", e);
        }
    }

    // ==================== Main 계좌 랭킹 ====================

    /**
     * Main 계좌 전체 랭킹 조회 (잔액순)
     * - isDefault = true인 계좌만 조회
     * - 로컬 캐시 사용 (70초 TTL)
     *
     * @return RankingResponse (contestId = null, sortBy = "balance")
     */
    @Cacheable(value = "rankings", key = "'main:balance'")
    public RankingResponse getMainRankings() {
        log.info("📊 Main 계좌 랭킹 조회 (DB에서 로드)");

        // 1. DB에서 Main 계좌 전체 조회 (잔액 내림차순)
        List<Account> accounts = accountRepository.findMainAccountsOrderByBalance();

        // 2. Account → RankingDto 변환 (순위 부여)
        List<RankingDto> rankings = convertToRankingDtos(accounts, false);

        // 3. 전체 인원 수
        Long totalParticipants = accountRepository.countMainAccounts();

        // 4. 응답 생성
        return RankingResponse.builder()
                .contestId(null) // Main 계좌는 contestId 없음
                .contestName("Main 계좌")
                .sortBy("balance")
                .rankings(rankings)
                .totalParticipants(totalParticipants)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 대회 계좌 랭킹 ====================

    /**
     * 특정 대회 전체 랭킹 조회
     * - 잔액순 (sortBy = "balance")
     * - 수익률순 (sortBy = "returnRate")
     * - 로컬 캐시 사용 (70초 TTL)
     *
     * @param contestId 대회 ID
     * @param sortBy    정렬 기준 ("balance" | "returnRate")
     * @return RankingResponse
     */
    @Cacheable(value = "rankings", key = "'contest:' + #contestId + ':' + #sortBy")
    public RankingResponse getContestRankings(Long contestId, String sortBy) {
        log.info("📊 대회 [{}] 랭킹 조회 (sortBy: {}) - DB에서 로드", contestId, sortBy);

        // 1. 대회 존재 여부 확인
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("대회를 찾을 수 없습니다. (ID: " + contestId + ")"));

        // 2. sortBy에 따라 DB 조회
        List<Account> accounts;
        boolean isReturnRate = "returnRate".equalsIgnoreCase(sortBy);

        if (isReturnRate) {
            // 수익률순 조회
            accounts = accountRepository.findByContestIdOrderByReturnRate(contestId);
        } else {
            // 잔액순 조회 (기본값)
            accounts = accountRepository.findByContestIdOrderByBalance(contestId);
        }

        // 3. Account → RankingDto 변환 (순위 부여, 수익률 계산)
        List<RankingDto> rankings = convertToRankingDtos(accounts, isReturnRate);

        // 4. 전체 인원 수
        Long totalParticipants = accountRepository.countByContest_ContestId(contestId);

        // 5. 응답 생성
        return RankingResponse.builder()
                .contestId(contestId)
                .contestName(contest.getContestName())
                .sortBy(isReturnRate ? "returnRate" : "balance")
                .rankings(rankings)
                .totalParticipants(totalParticipants)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    // ==================== 내 랭킹 조회 ====================

    /**
     * 내 랭킹 정보 조회
     * - Main 계좌: 잔액 순위만 제공
     * - 대회 계좌: 잔액 순위 + 수익률 순위 제공
     *
     * @param memberId  회원 ID
     * @param contestId 대회 ID (null이면 Main 계좌)
     * @return MyRankDto
     */
    public MyRankDto getMyRank(Long memberId, Long contestId) {
        log.info("🔍 내 랭킹 조회 (memberId: {}, contestId: {})", memberId, contestId);

        // 1. 내 계좌 찾기
        Account myAccount = findMyAccount(memberId, contestId);

        // 2. Main 계좌인 경우
        if (contestId == null) {
            Long balanceRank = accountRepository.findMyRankInMainByBalance(myAccount.getCash());
            Long totalParticipants = accountRepository.countMainAccounts();

            return MyRankDto.builder()
                    .balanceRank(balanceRank)
                    .returnRateRank(null) // Main 계좌는 수익률 없음
                    .totalParticipants(totalParticipants)
                    .myBalance(myAccount.getCash())
                    .myReturnRate(null)
                    .build();
        }

        // 3. 대회 계좌인 경우
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new IllegalArgumentException("대회를 찾을 수 없습니다. (ID: " + contestId + ")"));

        // 3-1. 내 잔액 순위
        Long balanceRank = accountRepository.findMyRankInContestByBalance(contestId, myAccount.getCash());

        // 3-2. 내 수익률 계산
        BigDecimal myReturnRate = calculateReturnRate(myAccount, contest);

        // 3-3. 내 수익률 순위
        Long returnRateRank = accountRepository.findMyRankInContestByReturnRate(contestId, myReturnRate);

        // 3-4. 전체 인원 수
        Long totalParticipants = accountRepository.countByContest_ContestId(contestId);

        return MyRankDto.builder()
                .balanceRank(balanceRank)
                .returnRateRank(returnRateRank)
                .totalParticipants(totalParticipants)
                .myBalance(myAccount.getCash())
                .myReturnRate(myReturnRate)
                .build();
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
            // Main 계좌 조회
            return accountRepository.findMainAccountsOrderByBalance().stream()
                    .filter(account -> account.getMember().getMemberId().equals(memberId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Main 계좌를 찾을 수 없습니다."));
        } else {
            // 대회 계좌 조회
            return accountRepository.findByContestIdOrderByBalance(contestId).stream()
                    .filter(account -> account.getMember().getMemberId().equals(memberId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("대회 계좌를 찾을 수 없습니다."));
        }
    }

    /**
     * Account 리스트를 RankingDto 리스트로 변환
     * - 순위 부여 (1위부터 시작)
     * - 수익률 계산 (대회 계좌만)
     *
     * @param accounts      Account 리스트 (정렬된 상태)
     * @param includeReturn 수익률 포함 여부
     * @return RankingDto 리스트
     */
    private List<RankingDto> convertToRankingDtos(List<Account> accounts, boolean includeReturn) {
        List<RankingDto> rankings = new ArrayList<>();
        int rank = 1;

        for (Account account : accounts) {
            BigDecimal returnRate = null;

            // 수익률 계산 (대회 계좌만)
            if (includeReturn && account.getContest() != null) {
                returnRate = calculateReturnRate(account, account.getContest());
            }

            RankingDto dto = RankingDto.builder()
                    .rank(rank++)
                    .memberId(account.getMember().getMemberId())
                    .nickname(account.getMember().getName())
                    .balance(account.getCash())
                    .returnRate(returnRate)
                    .build();

            rankings.add(dto);
        }

        return rankings;
    }

    /**
     * 수익률 계산
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
}

