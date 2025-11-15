package grit.stockIt.domain.member.repository;

import grit.stockIt.domain.member.entity.FavoriteStock;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FavoriteStockRepository extends JpaRepository<FavoriteStock, Long> {

    Optional<FavoriteStock> findByMemberAndStock(Member member, Stock stock);

    List<FavoriteStock> findAllByMember(Member member);

    void deleteByMemberAndStock(Member member, Stock stock);
}
