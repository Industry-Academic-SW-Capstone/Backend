package grit.stockIt.domain.settlement.repository;

import grit.stockIt.domain.settlement.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    boolean existsByExecutionId(Long executionId);
}
