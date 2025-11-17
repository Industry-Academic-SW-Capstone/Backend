package grit.stockIt.domain.execution.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    List<Execution> findByOrder(Order order);

    List<Execution> findByAccount(Account account);

    List<Execution> findByStock(Stock stock);
}

