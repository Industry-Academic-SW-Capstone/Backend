package grit.stockIt.domain.account.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountStockRepository extends JpaRepository<AccountStock, Long> {

    Optional<AccountStock> findByAccountAndStock(Account account, Stock stock);

    // 계좌의 보유종목 목록 조회 (Stock JOIN FETCH로 N+1 방지)
    @Query("SELECT as FROM AccountStock as " +
           "JOIN FETCH as.stock s " +
           "WHERE as.account.accountId = :accountId " +
           "AND as.quantity > 0 " +
           "ORDER BY s.name")
    List<AccountStock> findByAccountIdWithStock(@Param("accountId") Long accountId);
}
