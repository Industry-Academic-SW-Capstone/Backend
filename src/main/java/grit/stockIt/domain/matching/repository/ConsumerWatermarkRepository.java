package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.entity.ConsumerWatermarkId;
import org.springframework.data.jpa.repository.JpaRepository;

// 쓰기는 ConsumerWatermarkWriter 가 한다. 여기는 조회용이다.
public interface ConsumerWatermarkRepository extends JpaRepository<ConsumerWatermark, ConsumerWatermarkId> {
}
