package grit.stockIt.domain.account.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    // 회원의 기본 계좌를 찾음 (isDefault = true)
    Optional<Account> findByMemberAndIsDefaultTrue(Member member);

    // Main 계좌 전체 랭킹 조회 (잔액 순)
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "WHERE a.isDefault = true " +
           "ORDER BY a.cash DESC")
    List<Account> findMainAccountsOrderByBalance();

    // 특정 대회 전체 랭킹 조회 (잔액 순)
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "WHERE a.contest.contestId = :contestId " +
           "ORDER BY a.cash DESC")
    List<Account> findByContestIdOrderByBalance(@Param("contestId") Long contestId);

    // 특정 대회 전체 랭킹 조회 (수익률 순)
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "JOIN FETCH a.contest c " +
           "WHERE a.contest.contestId = :contestId " +
           "ORDER BY (a.cash - c.seedMoney) / c.seedMoney DESC")
    List<Account> findByContestIdOrderByReturnRate(@Param("contestId") Long contestId);

    // Main 계좌 총 인원 수
    @Query("SELECT COUNT(a) FROM Account a WHERE a.isDefault = true")
    Long countMainAccounts();

    // 특정 대회 총 인원 수
    Long countByContest_ContestId(Long contestId);

    // Main 계좌에서 내 순위 조회 (잔액 기준)
    @Query("SELECT COUNT(a) + 1 FROM Account a " +
           "WHERE a.isDefault = true " +
           "AND a.cash > :myBalance")
    Long findMyRankInMainByBalance(@Param("myBalance") BigDecimal myBalance);

    // 특정 대회에서 내 순위 조회 (잔액 기준)
    @Query("SELECT COUNT(a) + 1 FROM Account a " +
           "WHERE a.contest.contestId = :contestId " +
           "AND a.cash > :myBalance")
    Long findMyRankInContestByBalance(@Param("contestId") Long contestId, 
                                       @Param("myBalance") BigDecimal myBalance);

    // 특정 대회에서 내 순위 조회 (수익률 기준)
    @Query("SELECT COUNT(a) + 1 FROM Account a " +
           "JOIN a.contest c " +
           "WHERE a.contest.contestId = :contestId " +
           "AND (a.cash - c.seedMoney) / c.seedMoney > :myReturnRate")
    Long findMyRankInContestByReturnRate(@Param("contestId") Long contestId, 
                                         @Param("myReturnRate") BigDecimal myReturnRate);

    // Main 계좌 조회 (회원 ID로)
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "WHERE a.member.memberId = :memberId " +
           "AND a.isDefault = true")
    Optional<Account> findByMemberIdAndIsDefaultTrue(@Param("memberId") Long memberId);

    // 대회 계좌 조회 (회원 ID + 대회 ID로)
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "JOIN FETCH a.contest c " +
           "WHERE a.member.memberId = :memberId " +
           "AND a.contest.contestId = :contestId")
    Optional<Account> findByMemberIdAndContestId(@Param("memberId") Long memberId, 
                                                 @Param("contestId") Long contestId);

    // 계좌 조회 (비관적 락, Member와 Contest 함께 조회하여 N+1 문제 방지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a " +
           "JOIN FETCH a.member m " +
           "JOIN FETCH a.contest c " +
           "WHERE a.accountId = :accountId")
    Optional<Account> findByIdWithLock(@Param("accountId") Long accountId);
}