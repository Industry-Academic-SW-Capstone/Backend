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
     * 각 업종별 최대 5개까지 반환 (있는 만큼만)
     * @return 업종별 인기 종목 리스트
     */
    @GetMapping("/by-industry")
    public Mono<List<IndustryStockRankingDto>> getPopularStocksByIndustry() {
        log.info("업종별 인기 종목 조회 요청 (각 업종별 최대 5개)");
        
        // 고정된 업종 코드 리스트
        List<String> industryCodes = List.of(
                "0027",  // 제조/화학/제약/전자
                "0029",  // IT/소프트웨어/게임
                "1009",  // 제조/기계/전자
                "1006",  // IT서비스/소프트웨어
                "1014"   // 금융/투자
        );
        
        return stockRankingService.getPopularStocksByIndustry(30, industryCodes)
                .doOnSuccess(result -> 
                    log.info("업종별 인기 종목 조회 완료 - {}개 업종", result.size())
                )
                .doOnError(error -> 
                    log.error("업종별 인기 종목 조회 중 오류 발생", error)
                );
    }
}

