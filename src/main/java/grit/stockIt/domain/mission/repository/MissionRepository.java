package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.mission.enums.MissionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query; // Query 임포트

import java.util.List;
import java.util.Optional;

public interface MissionRepository extends JpaRepository<Mission, Long> {

    // (기존 메서드...)
    List<Mission> findAllByTrack(MissionTrack track);

    /**
     * [신규] 특정 트랙의 특정 조건 타입을 가진 미션을 조회합니다.
     * (예: 'ACHIEVEMENT' 트랙의 'LOGIN_STREAK' 미션 찾기)
     */
    Optional<Mission> findByTrackAndConditionType(MissionTrack track, MissionConditionType conditionType);

    List<Mission> findAllByTrackInAndType(List<MissionTrack> tracks, MissionType type);
}