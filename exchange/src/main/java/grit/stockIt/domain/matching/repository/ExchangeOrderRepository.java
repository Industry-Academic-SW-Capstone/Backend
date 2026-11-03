package grit.stockIt.domain.matching.repository;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.entity.ExchangeOrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExchangeOrderRepository extends JpaRepository<ExchangeOrder, Long> {

    @Query("SELECT o FROM ExchangeOrder o WHERE o.orderType = :orderType AND o.status = :status")
    List<ExchangeOrder> findByTypeAndStatus(@Param("orderType") OrderType orderType,
                                            @Param("status") ExchangeOrderStatus status);

    @Query("SELECT new grit.stockIt.contract.ExchangeQueryClient$OpenOrder(o.orderId, o.stockCode) "
            + "FROM ExchangeOrder o WHERE o.status = :status")
    List<ExchangeQueryClient.OpenOrder> findOrdersByStatus(@Param("status") ExchangeOrderStatus status);
}
