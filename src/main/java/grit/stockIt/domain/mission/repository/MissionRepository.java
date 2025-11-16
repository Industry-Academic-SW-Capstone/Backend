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
    // --- ⬇️ [신규] 초기 미션 조회를 위한 메서드 추가 ⬇️ ---

    /**
     * 트랙(단타, 스윙, 장기)의 '첫 번째 중급 미션'들을 조회합니다.
     * (INTERMEDIATE 타입이면서, 선행 미션이 없는 미션 = 즉, 다른 미션의 nextMission으로 지정되지 않은 미션)
     * * [수정] 또는 더 간단하게, 'INTERMEDIATE'이면서 'nextMission'이 null이 아닌,
     * 각 트랙의 첫 단계 미션을 찾습니다. (이 방식은 기획에 따라 쿼리가 복잡해질 수 있음)
     *
     * 여기서는 가장 단순한 방식을 사용합니다:
     * 'INTERMEDIATE' 타입이면서 'track'이 단타, 스윙, 장기 중 하나인 미션
     * (만약 중급 1단계, 중급 2단계가 있다면, '선행 미션 ID(prevMissionId)'가 null인 것을 찾아야 함)
     *
     * -> 여기서는 nextMission을 기준으로 1단계를 판별하겠습니다.
     * (중급 1단계는 nextMission(중급 2단계)을 가짐)
     * (하지만 prevMissionId가 없는게 더 명확합니다. 여기서는 nextMissionId 기준으로 구현합니다.)
     */

    // [대안] 'INTERMEDIATE' 타입이면서 prevMissionId가 없는 모든 미션
    // (Mission 엔티티에 prevMissionId가 없으므로, 다른 방식으로 조회)

    /**
     * [최종안]
     * 트랙(단타, 스윙, 장기)의 '중급(INTERMEDIATE)' 미션과
     * '업적(ACHIEVEMENT)' 미션, '일일(DAILY)' 미션을 모두 조회합니다.
     *
     * -> 중급/고급은 첫 미션만 활성화해야 하므로, 로직 수정이 필요합니다.
     */

    /**
     * [최종안 2] MissionService에서 분리 조회
     * 1. 모든 일일 미션 조회
     */
    //List<Mission> findAllByTrack(MissionTrack track); // (track = DAILY)

    /**
     * 2. 모든 업적 미션 조회
     */
    // findAllByTrack(MissionTrack.ACHIEVEMENT) 재사용

    /**
     * 3. 각 트랙(단타, 스윙, 장기)의 첫 번째 미션(중급 1단계) 조회
     * (여기서는 INTERMEDIATE 타입이면서, 다른 미션의 nextMission이 아닌 미션 = 즉, prevMission이 없는 미션)
     * (Mission 엔티티 구조상 prevMissionId가 없으므로,
     * INTERMEDIATE 타입이면서 nextMissionId가 있는 미션 중 ID가 가장 낮은 것을 조회하거나,
     * 혹은 수동으로 1단계 미션들에 플래그를 달아야 합니다.)
     *
     * [가정] 여기서는 'INTERMEDIATE' 타입인 미션은 모두 '1단계' 미션이라고 가정합니다.
     * (만약 INTERMEDIATE 1, 2, 3이 있다면 이 쿼리는 수정되어야 합니다.)
     */
    List<Mission> findAllByTrackInAndType(List<MissionTrack> tracks, MissionType type);
}