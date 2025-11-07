package grit.stockIt.domain.contest.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import grit.stockIt.domain.contest.entity.Contest;

import java.util.Optional;

@Repository
public interface ContestRepository extends JpaRepository<Contest, Long> {
    Optional<Contest> findByIsDefaultTrue();
}