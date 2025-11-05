package grit.stockIt.domain.stock.controller;

import grit.stockIt.domain.stock.dto.IndustryStockRankingDto;
import grit.stockIt.domain.stock.dto.StockRankingDto;
import grit.stockIt.domain.stock.service.StockRankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

// 주식 순위 정보 조회
@Slf4j
@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
public class StockRankingController {

    private final StockRankingService stockRankingService;

    @GetMapping("/amount")
    public Mono<List<StockRankingDto>> getAmountTopStocks() {
        
        log.info("거래대금 상위 20개 종목 조회 요청");
        
        return stockRankingService.getAmountTopStocksFiltered(30)
                .doOnSuccess(stocks -> 
                    log.info("거래대금 상위 {}개 종목 조회 완료", stocks.size())
                )
                .doOnError(error -> 
                    log.error("거래대금 상위 종목 조회 중 오류 발생", error)
                );
    }

    /**
     * 업종별 인기 종목 조회
     * 거래대금 상위 30개에서 실제로 나타나는 업종을 동적으로 감지하여 반환
     * 각 업종별 최대 5개까지 반환 (있는 만큼만)
     * @return 업종별 인기 종목 리스트
     */
    @GetMapping("/by-industry")
    public Mono<List<IndustryStockRankingDto>> getPopularStocksByIndustry() {
        log.info("업종별 인기 종목 조회 요청 (동적 감지, 각 업종별 최대 5개)");
        
        return stockRankingService.getPopularStocksByIndustry(30)
                .doOnSuccess(result -> 
                    log.info("업종별 인기 종목 조회 완료 - {}개 업종", result.size())
                )
                .doOnError(error -> 
                    log.error("업종별 인기 종목 조회 중 오류 발생", error)
                );
    }
}

