package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MissionProgressRepository extends JpaRepository<MissionProgress, Long> {

    Optional<MissionProgress> findByMemberIdAndMission(Long memberId, Mission mission);

    List<MissionProgress> findByMemberIdAndStatus(Long memberId, MissionStatus status);

    // --- [추가] 시드 복사기 업적용: 완료된 미션 개수 카운트 ---
    long countByMemberIdAndStatus(Long memberId, MissionStatus status);

    List<MissionProgress> findAllByMission_Track(MissionTrack track);

    List<MissionProgress> findAllByMemberIdAndMission_Track(Long memberId, MissionTrack track);

    @Query("SELECT mp FROM MissionProgress mp " +
            "JOIN FETCH mp.mission m " +
            "LEFT JOIN FETCH m.reward r " +
            "WHERE mp.memberId = :memberId")
    List<MissionProgress> findByMemberWithMissionAndReward(@Param("memberId") Long memberId);

    @Query("SELECT mp FROM MissionProgress mp JOIN FETCH mp.mission m " +
            "WHERE mp.memberId = :memberId " +
            "AND m.track = :track " +
            "AND m.conditionType = :conditionType")
    Optional<MissionProgress> findByMemberAndMissionTypeWithMission(
            @Param("memberId") Long memberId,
            @Param("track") MissionTrack track,
            @Param("conditionType") MissionConditionType conditionType
    );

    @Query("SELECT mp FROM MissionProgress mp JOIN FETCH mp.mission " +
            "WHERE mp.memberId = :memberId AND mp.status = :status")
    List<MissionProgress> findByMemberAndStatusWithMission(
            @Param("memberId") Long memberId,
            @Param("status") MissionStatus status
    );

    // --- [추가] 스케줄러용: 특정 조건 타입(HOLDING_DAYS)이면서 진행 중인 미션 조회 ---
    List<MissionProgress> findAllByMission_ConditionTypeAndStatus(
            MissionConditionType conditionType,
            MissionStatus status
    );

    // [핵심 변경] 대시보드용: 같은 조건(LOGIN_STREAK)의 미션 중 '목표치(GoalValue)'가 가장 큰 1개만 조회
    // 이렇게 하면 7일, 15일 짜리가 아니라 100,000일 짜리 '트래커' 미션이 선택됨
    @Query("SELECT mp FROM MissionProgress mp " +
            "JOIN FETCH mp.mission m " +
            "WHERE mp.memberId = :memberId " +
            "AND m.track = :track " +
            "AND m.conditionType = :conditionType " +
            "ORDER BY m.goalValue DESC " + // 목표치가 가장 높은 순 정렬
            "LIMIT 1")                     // 1개만 가져오기
    Optional<MissionProgress> findTopByMemberAndConditionOrderByGoalDesc(
            @Param("memberId") Long memberId,
            @Param("track") MissionTrack track,
            @Param("conditionType") MissionConditionType conditionType
    );

    // [신규 추가] 다건 조회용 (연속 출석 초기화 등) -> List 반환!
    // 메서드 이름에 'All'을 붙여서 구분합니다.
    @Query("SELECT mp FROM MissionProgress mp JOIN FETCH mp.mission m " +
            "WHERE mp.memberId = :memberId " +
            "AND m.track = :track " +
            "AND m.conditionType = :conditionType")
    List<MissionProgress> findAllByMemberAndMissionTypeWithMission(
            @Param("memberId") Long memberId,
            @Param("track") MissionTrack track,
            @Param("conditionType") MissionConditionType conditionType
    );

    // [추가] 조건 타입으로 모든 유저의 진행도 조회 (LOGIN_COUNT 조회용)
    List<MissionProgress> findAllByMission_ConditionType(MissionConditionType conditionType);

    /**
     * [리팩토링 완료] 연속 출석 초기화 벌크 연산
     * - 문자열 하드코딩 제거 -> Enum 파라미터 바인딩 사용 (:paramName)
     * - 불필요한 중복 조건 제거 (currentValue < goalValue)
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MissionProgress mp " +
            "SET mp.currentValue = 0 " +
            "WHERE mp.mission.track = :streakTrack " +
            "AND mp.mission.conditionType = :streakCondition " +
            "AND mp.currentValue > 0 " +
            "AND mp.memberId IN (" +
            "SELECT dmp.memberId " +
            "FROM MissionProgress dmp " +
            "WHERE dmp.mission.track = :dailyTrack " +
            "AND dmp.mission.conditionType = :dailyCondition " +
            "AND dmp.status != :completedStatus" +
            ")")
    int bulkResetLoginStreakForAbsentees(
            @Param("streakTrack") MissionTrack streakTrack,
            @Param("streakCondition") MissionConditionType streakCondition,
            @Param("dailyTrack") MissionTrack dailyTrack,
            @Param("dailyCondition") MissionConditionType dailyCondition,
            @Param("completedStatus") MissionStatus completedStatus
    );
}