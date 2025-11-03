package grit.stockIt.domain.user.repository;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.user.entity.UserMission;
import grit.stockIt.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserMissionRepository extends JpaRepository<UserMission, Long> {
    List<UserMission> findByUser(User user);
    Optional<UserMission> findByUserAndMission(User user, Mission mission);
}