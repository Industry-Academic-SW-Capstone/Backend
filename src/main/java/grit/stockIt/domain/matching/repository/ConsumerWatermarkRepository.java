package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.entity.ConsumerWatermarkId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConsumerWatermarkRepository extends JpaRepository<ConsumerWatermark, ConsumerWatermarkId> {

    // 1 이면 처음 보는 위치, 0 이면 이미 반영한 위치(재전달)다. 행이 없으면 만들므로 미리 심을 필요가 없다.
    @Modifying
    @Query(value = """
            INSERT INTO consumer_watermark (topic, partition_no, last_offset, updated_at)
            VALUES (:topic, :partition, :offset, now())
            ON CONFLICT (topic, partition_no) DO UPDATE
               SET last_offset = EXCLUDED.last_offset, updated_at = EXCLUDED.updated_at
             WHERE consumer_watermark.last_offset < EXCLUDED.last_offset
            """, nativeQuery = true)
    int advance(@Param("topic") String topic, @Param("partition") int partition, @Param("offset") long offset);
}
