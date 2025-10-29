package grit.stockIt.domain.stock.controller;

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
        
        return stockRankingService.getAmountTopStocksFiltered(20)
                .doOnSuccess(stocks -> 
                    log.info("거래대금 상위 {}개 종목 조회 완료", stocks.size())
                )
                .doOnError(error -> 
                    log.error("거래대금 상위 종목 조회 중 오류 발생", error)
                );
    }
}

