package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionConditionType;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MissionProgressRepository extends JpaRepository<MissionProgress, Long> {

    Optional<MissionProgress> findByMemberAndMission(Member member, Mission mission);

    List<MissionProgress> findByMemberAndStatus(Member member, MissionStatus status);

    // --- ⬇️ [추가] 시드 복사기 업적용: 완료된 미션 개수 카운트 ⬇️ ---
    long countByMemberAndStatus(Member member, MissionStatus status);

    List<MissionProgress> findAllByMission_Track(MissionTrack track);

    List<MissionProgress> findAllByMemberAndMission_Track(Member member, MissionTrack track);

    @Query("SELECT mp FROM MissionProgress mp " +
            "JOIN FETCH mp.mission m " +
            "LEFT JOIN FETCH m.reward r " +
            "WHERE mp.member = :member")
    List<MissionProgress> findByMemberWithMissionAndReward(@Param("member") Member member);

    @Query("SELECT mp FROM MissionProgress mp JOIN FETCH mp.mission m " +
            "WHERE mp.member = :member " +
            "AND m.track = :track " +
            "AND m.conditionType = :conditionType")
    Optional<MissionProgress> findByMemberAndMissionTypeWithMission(
            @Param("member") Member member,
            @Param("track") MissionTrack track,
            @Param("conditionType") MissionConditionType conditionType
    );

    @Query("SELECT mp FROM MissionProgress mp JOIN FETCH mp.mission " +
            "WHERE mp.member = :member AND mp.status = :status")
    List<MissionProgress> findByMemberAndStatusWithMission(
            @Param("member") Member member,
            @Param("status") MissionStatus status
    );

    // --- ⬇️ [추가] 스케줄러용: 특정 조건 타입(HOLDING_DAYS)이면서 진행 중인 미션 조회 ⬇️ ---
    List<MissionProgress> findAllByMission_ConditionTypeAndStatus(
            MissionConditionType conditionType,
            MissionStatus status
    );
}