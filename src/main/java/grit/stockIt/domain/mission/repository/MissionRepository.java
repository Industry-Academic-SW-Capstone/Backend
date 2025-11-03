package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.mission.entity.Mission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MissionRepository extends JpaRepository<Mission, Long> {
    // Mission 마스터 테이블은 findAll()만 사용해도 충분합니다.
}