package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.entity.ConsumerWatermarkId;
import org.springframework.data.jpa.repository.JpaRepository;

// 쓰기는 StockMatchingLock#acquireAndAdvance 가 종목 락과 한 왕복에 한다.
public interface ConsumerWatermarkRepository extends JpaRepository<ConsumerWatermark, ConsumerWatermarkId> {
}
