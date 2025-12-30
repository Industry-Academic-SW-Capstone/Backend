package grit.stockIt.domain.industry.repository;

import grit.stockIt.domain.industry.entity.Industry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndustryRepository extends JpaRepository<Industry,String> {
}
