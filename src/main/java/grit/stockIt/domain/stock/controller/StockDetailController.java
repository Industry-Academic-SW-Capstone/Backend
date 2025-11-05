package grit.stockIt.domain.stock.controller;

import grit.stockIt.domain.stock.dto.StockDetailDto;
import grit.stockIt.domain.stock.service.StockDetailService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * 주식 상세 정보 조회 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/stocks")
@Tag(name = "stocks", description = "주식 관련 API")
@RequiredArgsConstructor
public class StockDetailController {

    private final StockDetailService stockDetailService;

    /**
     * 주식 상세 정보 조회
     * @param stockCode 종목코드 (6자리)
     * @return 주식 상세 정보 (현재가, 시가총액, PER 등)
     */
    @GetMapping("/{stockCode}")
    public Mono<StockDetailDto> getStockDetail(@PathVariable String stockCode) {
        log.info("주식 상세 정보 조회 요청: {}", stockCode);
        
        return stockDetailService.getStockDetail(stockCode)
                .doOnSuccess(detail -> 
                    log.info("주식 상세 정보 조회 완료: {} - {}", stockCode, detail.stockName())
                )
                .doOnError(error -> 
                    log.error("주식 상세 정보 조회 중 오류 발생: {}", stockCode, error)
                );
    }
}

