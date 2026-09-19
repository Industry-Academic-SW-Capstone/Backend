package grit.stockIt.domain.settlement.repository;

import grit.stockIt.domain.settlement.entity.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    boolean existsByExecutionId(Long executionId);

    // 아직 정산되지 않은 체결. settlement 쪽에서 execution 을 바라보는 방향이라
    // 체결 도메인이 정산을 알 필요가 없다(외래키도 같은 방향이다).
    //
    // cutoff 가 필요한 이유: 체결 직후 정산이 돌고 있는 건을 배치가 가로채면 유니크 제약에
    // 걸려 한쪽이 헛돈다. 방금 만들어진 체결은 아직 손대지 않는다.
    @Query(value = """
            SELECT e.execution_id
            FROM execution e
            WHERE e.created_at < :cutoff
              AND NOT EXISTS (
                  SELECT 1 FROM settlement s WHERE s.execution_id = e.execution_id
              )
            ORDER BY e.execution_id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findUnsettledExecutionIds(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Query(value = """
            SELECT count(*)
            FROM execution e
            WHERE e.created_at < :cutoff
              AND NOT EXISTS (
                  SELECT 1 FROM settlement s WHERE s.execution_id = e.execution_id
              )
            """, nativeQuery = true)
    long countUnsettled(@Param("cutoff") LocalDateTime cutoff);
}
