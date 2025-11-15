package grit.stockIt.domain.mission.repository;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.mission.enums.MissionStatus;
import grit.stockIt.domain.mission.enums.MissionTrack;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Query; // 1. 임포트 추가
import org.springframework.data.repository.query.Param; // 2. 임포트 추가

import java.util.List;
import java.util.Optional;

public interface MissionProgressRepository extends JpaRepository<MissionProgress, Long> {

    /**
     * 특정 회원의 특정 미션 진행도를 조회합니다. (FindOrCreate 로직의 핵심)
     */
    Optional<MissionProgress> findByMemberAndMission(Member member, Mission mission);

    /**
     * 특정 회원의 특정 상태(예: IN_PROGRESS)인 모든 미션 진행도를 조회합니다.
     * (이벤트 발생 시 갱신 대상을 찾는 핵심 메서드)
     */
    List<MissionProgress> findByMemberAndStatus(Member member, MissionStatus status);

    /**
     * 특정 트랙의 모든 '일일 미션' 진행도를 조회합니다. (스케줄러의 일괄 초기화용)
     */
    List<MissionProgress> findAllByMission_Track(MissionTrack track); // (track = DAILY)

    /**
     * 특정 회원의 특정 트랙에 해당하는 모든 미션 진행도를 조회합니다.
     * (트랙 초기화 로직용)
     */
    List<MissionProgress> findAllByMemberAndMission_Track(Member member, MissionTrack track);

    /**
     * 특정 회원의 모든 미션 진행도를 '미션(Mission)'과 '보상(Reward)' 정보와 함께 조회합니다.
     * (N+1 문제 해결용)
     */
    @Query("SELECT mp FROM MissionProgress mp " +
            "JOIN FETCH mp.mission m " +
            "LEFT JOIN FETCH m.reward r " + // 보상은 없을 수도 있으므로 LEFT JOIN
            "WHERE mp.member = :member")
    List<MissionProgress> findByMemberWithMissionAndReward(@Param("member") Member member);
}