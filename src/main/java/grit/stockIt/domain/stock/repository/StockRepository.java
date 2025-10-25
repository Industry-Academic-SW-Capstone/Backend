package grit.stockIt.domain.stock.repository;

import grit.stockIt.domain.stock.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockRepository extends JpaRepository<Stock,String> {
    List<Stock> findByMarketType(String marketType);
    
    // 종목 코드 리스트로 존재하는 종목들만 조회
    List<Stock> findByCodeIn(List<String> stockCodes);
}
