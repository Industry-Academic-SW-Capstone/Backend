package grit.stockIt.domain.stock.controller;

import grit.stockIt.domain.stock.dto.StockChartDto;
import grit.stockIt.domain.stock.dto.StockDetailDto;
import grit.stockIt.domain.stock.service.StockChartService;
import grit.stockIt.domain.stock.service.StockDetailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

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
    private final StockChartService stockChartService;

    /**
     * 주식 상세 정보 조회
     * @param stockCode 종목코드 (6자리)
     * @return 주식 상세 정보 (현재가, 시가총액, PER 등)
     */
    @Operation(summary = "주식 상세 정보 조회", description = "종목코드로 주식의 현재가, 시가총액, PER, EPS, PBR 등 상세 정보를 조회합니다")
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

    /**
     * 주식 차트 데이터 조회
     * @param stockCode 종목코드 (6자리)
     * @param periodType 기간 타입 (1day/1week/3month/1year/5year)
     * @return 차트 데이터 리스트
     */
    @Operation(
            summary = "주식 차트 데이터 조회",
            description = "종목코드와 기간 타입으로 주식의 차트 데이터를 조회합니다. " +
                    "기간 타입: 1day(1일, 1분 간격), 1week(1주, 10분 간격), 3month(3달, 1일 간격), " +
                    "1year(1년, 7일 간격), 5year(5년, 1달 간격)."
    )
    @GetMapping("/{stockCode}/chart")
    public Mono<List<StockChartDto>> getStockChart(
            @PathVariable String stockCode,
            @RequestParam String periodType
    ) {
        return stockChartService.getStockChart(stockCode, periodType)
                .doOnError(error ->
                    log.error("주식 차트 데이터 조회 중 오류 발생: {} - {}", stockCode, periodType, error)
                );
    }
}

