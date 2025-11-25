package grit.stockIt.domain.execution.repository;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {

    List<Execution> findByOrder(Order order);

    List<Execution> findByAccount(Account account);

    List<Execution> findByStock(Stock stock);

    // 주문 ID 리스트로 체결 목록 조회 (주문별로 그룹화하기 위해)
    @Query("SELECT e FROM Execution e " +
           "WHERE e.order.orderId IN :orderIds " +
           "ORDER BY e.order.orderId, e.createdAt")
    List<Execution> findByOrderIdIn(@Param("orderIds") List<Long> orderIds);
}

