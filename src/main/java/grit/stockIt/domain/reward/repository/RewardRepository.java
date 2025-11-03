package grit.stockIt.domain.reward.repository;

import grit.stockIt.domain.reward.entity.Reward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RewardRepository extends JpaRepository<Reward, Long> {
    List<Reward> findByHasTitle(boolean hasTitle);

    List<Reward> findByHasMoney(boolean hasMoney);

    @Query("SELECT r FROM Reward r WHERE r.hasTitle = true AND r.hasMoney = true")
    List<Reward> findAllCombinedRewards();
}