package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.mission.entity.UserMissionProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserMissionProgressRepository extends JpaRepository<UserMissionProgress, Long> {

    // 1. 유저의 현재 유효한(만료되지 않은) 모든 미션 진행도 조회
    List<UserMissionProgress> findByUserIdAndExpiresAtAfter(Long userId, LocalDateTime now);

    // 2. 유저의 특정 미션 진행도 조회 (주기 무관) - 보상 수령 시 사용
    Optional<UserMissionProgress> findByUserIdAndMission_MissionId(Long userId, Long missionId);

    // 3. 유저의 현재 유효한 특정 미션 진행도 조회 - 서버 진행도 업데이트 시 사용
    Optional<UserMissionProgress> findByUserIdAndMission_MissionIdAndExpiresAtAfter(Long userId, Long missionId, LocalDateTime now);
}