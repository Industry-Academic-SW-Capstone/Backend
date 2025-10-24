package grit.stockIt.domain.stock.repository;

import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockRepository extends JpaRepository<Stock,String> {
    List<Stock> findByMarketType(String marketType);
}
