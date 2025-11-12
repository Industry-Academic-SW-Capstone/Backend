package grit.stockIt.domain.order.repository;

import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderIdAndStatus(Long orderId, OrderStatus status);

    List<Order> findAllByAccountAccountIdAndStatusIn(Long accountId, List<OrderStatus> statuses);
}

