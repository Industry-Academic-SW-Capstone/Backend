package grit.stockIt.domain.order.repository;

import grit.stockIt.domain.order.entity.OrderHold;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderHoldRepository extends JpaRepository<OrderHold, Long> {
}

