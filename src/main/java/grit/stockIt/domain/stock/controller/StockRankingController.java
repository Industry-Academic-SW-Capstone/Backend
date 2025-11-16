package grit.stockIt.domain.stock.controller;

import grit.stockIt.domain.stock.dto.IndustryStockRankingDto;
import grit.stockIt.domain.stock.dto.StockRankingDto;
import grit.stockIt.domain.stock.service.StockRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

// 주식 순위 정보 조회 컨트롤러 
@Slf4j
@RestController
@RequestMapping("/api/stocks")
@Tag(name = "stocks", description = "주식 관련 API")
@RequiredArgsConstructor
public class StockRankingController {

    private final StockRankingService stockRankingService;

    // 거래대금 상위 종목 조회 
    @Operation(summary = "거래대금 상위 종목 조회", description = "거래대금 기준 상위 30개 종목을 조회합니다")
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

    // 급등/급락 순위 조회
    @Operation(summary = "등락 순위 조회",description = "type 파라미터로 급등(rise)/급락(fall)을 지정하여 상위 30개 종목을 조회합니다.")
    @GetMapping("/fluctuations")
    public Mono<List<StockRankingDto>> getFluctuationTopStocks(@RequestParam(name = "type", defaultValue = "rise") String type) {
        String normalized = type == null ? "rise" : type.trim().toLowerCase();
        boolean isRise;
        if ("rise".equals(normalized)) {
            isRise = true;
        } else if ("fall".equals(normalized)) {
            isRise = false;
        } else {
            return Mono.error(new IllegalArgumentException("type 파라미터는 rise 또는 fall 이어야 합니다."));
        }

        log.info("등락 순위 조회 요청 - type: {}", normalized);

        Mono<List<StockRankingDto>> result = stockRankingService.getFluctuationTopStocksFiltered(30, isRise);

        return result
                .doOnSuccess(stocks ->
                        log.info("등락 순위 조회 완료 - type: {}, 반환: {}개", normalized, stocks.size())
                )
                .doOnError(error ->
                        log.error("등락 순위 조회 중 오류 발생 - type: {}", normalized, error)
                );
    }

    // 업종별 인기 종목 조회 
    @Operation(summary = "업종별 인기 종목 조회", description = "거래대금 상위 종목을 업종별로 그룹화하여 각 업종 최대 5개 종목을 반환합니다")
    @GetMapping("/industries")
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

