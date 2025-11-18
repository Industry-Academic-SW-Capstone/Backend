package grit.stockIt.domain.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.contest.entity.Contest;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByMemberAndContest(Member member, Contest contest);
    List<Account> findByMember(Member member);
    List<Account> findByContest(Contest contest);

    // --- ⬇️ [신규] MissionService를 위해 이 메서드 추가 ⬇️ ---
    /**
     * 회원의 '기본' 계좌를 찾습니다. (isDefault = true)
     */
    Optional<Account> findByMemberAndIsDefaultTrue(Member member);

    // ==================== 랭킹 관련 쿼리 ====================

    /**
     * 1. Main 계좌 전체 랭킹 조회 (잔액 순)
     * - isDefault = true인 계좌만 조회
     * - cash 내림차순 정렬
     */
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "WHERE a.isDefault = true " +
           "ORDER BY a.cash DESC")
    List<Account> findMainAccountsOrderByBalance();

    /**
     * 2. 특정 대회 전체 랭킹 조회 (잔액 순)
     * - contest_id로 필터링
     * - cash 내림차순 정렬
     */
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "WHERE a.contest.contestId = :contestId " +
           "ORDER BY a.cash DESC")
    List<Account> findByContestIdOrderByBalance(@Param("contestId") Long contestId);

    /**
     * 3. 특정 대회 전체 랭킹 조회 (수익률 순)
     * - 수익률 = (현재잔액 - 시드머니) / 시드머니 * 100
     * - 수익률 내림차순 정렬
     */
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "JOIN FETCH a.contest c " +
           "WHERE a.contest.contestId = :contestId " +
           "ORDER BY (a.cash - c.seedMoney) / c.seedMoney DESC")
    List<Account> findByContestIdOrderByReturnRate(@Param("contestId") Long contestId);

    /**
     * 4. Main 계좌 총 인원 수
     */
    @Query("SELECT COUNT(a) FROM Account a WHERE a.isDefault = true")
    Long countMainAccounts();

    /**
     * 5. 특정 대회 총 인원 수
     */
    Long countByContest_ContestId(Long contestId);

    /**
     * 6. Main 계좌에서 내 순위 조회 (잔액 기준)
     * - 내 잔액보다 많은 사람 수 + 1
     */
    @Query("SELECT COUNT(a) + 1 FROM Account a " +
           "WHERE a.isDefault = true " +
           "AND a.cash > :myBalance")
    Long findMyRankInMainByBalance(@Param("myBalance") BigDecimal myBalance);

    /**
     * 7. 특정 대회에서 내 순위 조회 (잔액 기준)
     * - 내 잔액보다 많은 사람 수 + 1
     */
    @Query("SELECT COUNT(a) + 1 FROM Account a " +
           "WHERE a.contest.contestId = :contestId " +
           "AND a.cash > :myBalance")
    Long findMyRankInContestByBalance(@Param("contestId") Long contestId, 
                                       @Param("myBalance") BigDecimal myBalance);

    /**
     * 8. 특정 대회에서 내 순위 조회 (수익률 기준)
     * - 내 수익률보다 높은 사람 수 + 1
     */
    @Query("SELECT COUNT(a) + 1 FROM Account a " +
           "JOIN a.contest c " +
           "WHERE a.contest.contestId = :contestId " +
           "AND (a.cash - c.seedMoney) / c.seedMoney > :myReturnRate")
    Long findMyRankInContestByReturnRate(@Param("contestId") Long contestId, 
                                         @Param("myReturnRate") BigDecimal myReturnRate);

    /**
     * 9. Main 계좌 조회 (회원 ID로)
     * - isDefault = true인 계좌만 조회
     * - Member JOIN FETCH로 N+1 문제 방지
     */
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "WHERE a.member.memberId = :memberId " +
           "AND a.isDefault = true")
    Optional<Account> findByMemberIdAndIsDefaultTrue(@Param("memberId") Long memberId);

    /**
     * 10. 대회 계좌 조회 (회원 ID + 대회 ID로)
     * - Member와 Contest JOIN FETCH로 N+1 문제 방지
     */
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "JOIN FETCH a.contest c " +
           "WHERE a.member.memberId = :memberId " +
           "AND a.contest.contestId = :contestId")
    Optional<Account> findByMemberIdAndContestId(@Param("memberId") Long memberId, 
                                                  @Param("contestId") Long contestId);
}