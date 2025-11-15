package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.mission.entity.Reward;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RewardRepository extends JpaRepository<Reward, Long> {
}