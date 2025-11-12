package grit.stockIt.domain.account.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccountStockRepository extends JpaRepository<AccountStock, Long> {

    Optional<AccountStock> findByAccountAndStock(Account account, Stock stock);
}
