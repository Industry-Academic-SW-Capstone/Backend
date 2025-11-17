package grit.stockIt.domain.contest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import grit.stockIt.domain.contest.entity.Contest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContestRepository extends JpaRepository<Contest, Long> {
    Optional<Contest> findByIsDefaultTrue();
    List<Contest> findAllByIsDefaultFalse();

    /**
     * 진행 중인 대회 조회
     * - 현재 시간이 시작일과 종료일 사이
     * - isDefault = false (Main 계좌 제외)
     */
    @Query("SELECT c FROM Contest c " +
           "WHERE c.startDate <= :now " +
           "AND (c.endDate IS NULL OR c.endDate >= :now) " +
           "AND c.isDefault = false")
    List<Contest> findActiveContests(@Param("now") LocalDateTime now);
}