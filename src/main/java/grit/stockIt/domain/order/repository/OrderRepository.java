package grit.stockIt.domain.order.repository;

import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderIdAndStatus(Long orderId, OrderStatus status);

    // 계좌의 대기주문 목록 조회
    @Query("SELECT o FROM Order o " +
           "JOIN FETCH o.stock s " +
           "WHERE o.account.accountId = :accountId " +
           "AND o.status IN :statuses " +
           "AND (o.quantity - o.filledQuantity) > 0 " +
           "ORDER BY o.createdAt DESC")
    List<Order> findAllPendingOrdersByAccountId(
            @Param("accountId") Long accountId,
            @Param("statuses") List<OrderStatus> statuses
    );
}

