package grit.stockIt.domain.account.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountStockRepository extends JpaRepository<AccountStock, Long> {

    Optional<AccountStock> findByAccountAndStock(Account account, Stock stock);

    @Query(value = "SELECT * FROM account_stock WHERE account_id = :accountId AND stock_code = :stockCode ORDER BY account_stock_id DESC LIMIT 1", nativeQuery = true)
    Optional<AccountStock> findAnyByAccountIdAndStockCode(@Param("accountId") Long accountId,
                                                          @Param("stockCode") String stockCode);
}

