package grit.stockIt.domain.account.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountStockRepository extends JpaRepository<AccountStock, Long> {

    Optional<AccountStock> findByAccountAndStock(Account account, Stock stock);

    // 계좌의 보유종목 목록 조회 (Stock JOIN FETCH로 N+1 방지)
    @Query("SELECT ast FROM AccountStock ast " +
           "JOIN FETCH ast.stock s " +
           "WHERE ast.account.accountId = :accountId " +
           "AND ast.quantity > 0 " +
           "ORDER BY s.name")
    List<AccountStock> findByAccountIdWithStock(@Param("accountId") Long accountId);


    // --- ⬇️ [추가] 스케줄러에서 홀딩 여부 체크용 ⬇️ ---
    // 회원의 계좌 중 수량이 0보다 큰 주식이 하나라도 있는지 확인
    boolean existsByAccount_MemberAndQuantityGreaterThan(Member member, int quantity);
    // --- ⬇️ [추가] 특정 계좌의 보유 주식 목록 전체 조회 ⬇️ ---
    List<AccountStock> findAllByAccount(Account account);

    // --- ⬇️ [추가] 모든 보유 주식의 중복 제거된 종목 코드 조회 ⬇️ ---
    @Query("SELECT DISTINCT ast.stock.code FROM AccountStock ast WHERE ast.quantity > 0")
    List<String> findDistinctStockCodes();
}