package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.entity.ExchangeFill;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExchangeFillRepository extends JpaRepository<ExchangeFill, Long> {
}
