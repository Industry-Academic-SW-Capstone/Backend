package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.mission.entity.Mission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MissionRepository extends JpaRepository<Mission, Long> {
    List<Mission> findByIsActiveTrue();
}