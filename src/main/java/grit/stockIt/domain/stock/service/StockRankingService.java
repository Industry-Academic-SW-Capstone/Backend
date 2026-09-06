package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.industry.repository.IndustryRepository;
import grit.stockIt.domain.stock.dto.IndustryStockRankingResponse;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import grit.stockIt.domain.stock.dto.KisRankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRankingService {

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;
    private final StockRepository stockRepository;
    private final IndustryRepository industryRepository;
    private final StockRankingParsingService stockRankingParsingService;
    private final IndustryRankingCalculationService industryRankingCalculationService;

    // 거래대금 상위 종목 조회 (비동기)
    public Mono<List<StockRankingResponse>> getAmountTopStocks(int limit) {
        String accessToken = kisTokenManager.getAccessToken();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/volume-rank")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_COND_SCR_DIV_CODE", "20171")
                        .queryParam("FID_INPUT_ISCD", "0000")
                        .queryParam("FID_DIV_CLS_CODE", "0")
                        .queryParam("FID_BLNG_CLS_CODE", "3") // 거래금액순 (0:평균거래량, 1:거래증가율, 2:평균거래회전율, 3:거래금액순, 4:평균거래금액회전율)
                        .queryParam("FID_TRGT_CLS_CODE", "111111111")
                        .queryParam("FID_TRGT_EXLS_CLS_CODE", "0000000000")
                        .queryParam("FID_INPUT_PRICE_1", "")
                        .queryParam("FID_INPUT_PRICE_2", "")
                        .queryParam("FID_VOL_CNT", "")
                        .queryParam("FID_INPUT_DATE_1", "")
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", "FHPST01710000")
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisRankingResponse.class)
                .map(response -> stockRankingParsingService.parseAmountRankingResponse(response, limit))
                .doOnError(e -> log.error("거래대금 상위 종목 조회 중 오류 발생", e))
                .onErrorResume(e -> Mono.error(new RuntimeException("거래대금 상위 종목 조회 실패", e)));
    }

    // 거래대금 상위 종목 조회 (DB에 있는 종목만 필터링) (비동기)
    public Mono<List<StockRankingResponse>> getAmountTopStocksFiltered(int limit) {
        return getAmountTopStocks(limit)
                .map(allStocks -> filterStocksInDatabase(allStocks, limit));
    }

    // 급등/급락 순위 조회 (비동기)
    public Mono<List<StockRankingResponse>> getFluctuationTopStocks(boolean rise) {
        String accessToken = kisTokenManager.getAccessToken();
        String trId = "FHPST01700000"; // 등락 순위 조회 TR ID

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/ranking/fluctuation")
                        // 문서 기준 소문자 파라미터 & 기본값 매핑
                        .queryParam("fid_cond_mrkt_div_code", "J")
                        .queryParam("fid_cond_scr_div_code", "20170")
                        .queryParam("fid_input_iscd", "0000")
                        .queryParam("fid_rank_sort_cls_code", rise ? "0" : "1") // 0:상승율순, 1:하락율순
                        .queryParam("fid_prc_cls_code", "1") // 종가대비
                        .queryParam("fid_input_cnt_1", "0") // 0:전체
                        .queryParam("fid_input_price_1", "")
                        .queryParam("fid_input_price_2", "")
                        .queryParam("fid_vol_cnt", "")
                        .queryParam("fid_trgt_cls_code", "0") // 0:전체
                        .queryParam("fid_trgt_exls_cls_code", "0") // 0:전체
                        .queryParam("fid_div_cls_code", "0") // 0:전체
                        .queryParam("fid_rsfl_rate1", "")
                        .queryParam("fid_rsfl_rate2", "")
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", trId)
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisRankingResponse.class)
                .map(response -> stockRankingParsingService.parseFluctuationRankingResponse(response, 30))
                .doOnError(e -> log.error("급{} 순위 조회 중 오류 발생", rise ? "등" : "락", e))
                .onErrorResume(e -> Mono.error(new RuntimeException("급" + (rise ? "등" : "락") + " 순위 조회 실패", e)));
    }

    // 등락 순위 조회 - DB 필터링 포함
    public Mono<List<StockRankingResponse>> getFluctuationTopStocksFiltered(int limit, boolean rise) {
        return getFluctuationTopStocks(rise)
                .map(allStocks -> filterStocksInDatabase(allStocks, limit));
    }

    // 업종별 인기 종목 조회 
    public Mono<List<IndustryStockRankingResponse>> getPopularStocksByIndustry(int totalLimit) {
        
        log.info("업종별 인기 종목 조회 시작 - 전체: {}개, 업종별 최대 5개 (동적 감지)", totalLimit);
        
        return getAmountTopStocksFiltered(totalLimit)
                .map(allStocks -> {
                    List<String> stockCodeList = allStocks.stream()
                            .map(StockRankingResponse::stockCode)
                            .filter(code -> code != null && !code.isEmpty())
                            .toList();
                    
                    // DB에서 일괄 조회 (N+1 문제 방지)
                    List<Stock> stockEntities = stockRepository.findByCodeIn(stockCodeList);
                    Map<String, Stock> stockMap = stockEntities.stream()
                            .collect(Collectors.toMap(Stock::getCode, stock -> stock));

                    IndustryRankingCalculationService.IndustryGrouping grouping =
                            industryRankingCalculationService.groupAndSort(allStocks, stockMap);

                    // 정렬된 업종코드를 그대로 넘긴다. 재정렬하면 동점 업종 순서가 바뀐다.
                    List<Industry> industries =
                            industryRepository.findAllById(grouping.sortedIndustryCodes());
                    Map<String, Industry> industryMap = industries.stream()
                            .collect(Collectors.toMap(Industry::getCode, industry -> industry));

                    final int maxPerIndustry = 5;
                    List<IndustryStockRankingResponse> result =
                            industryRankingCalculationService.selectTopStocks(
                                    grouping.stocksByIndustry(),
                                    grouping.sortedIndustryCodes(),
                                    industryMap,
                                    maxPerIndustry);

                    log.info("업종별 인기 종목 조회 완료 - {}개 업종 (동적 감지)", result.size());
                    return result;
                })
                .doOnError(e -> log.error("업종별 인기 종목 조회 중 오류 발생", e))
                .onErrorResume(e -> Mono.error(new RuntimeException("업종별 인기 종목 조회 실패", e)));
    }


    // 데이터베이스에 있는 종목만 필터링 (marketType을 DB에서 조회)
    private List<StockRankingResponse> filterStocksInDatabase(List<StockRankingResponse> stocks, int limit) {
        List<String> stockCodes = stocks.stream()
                .map(StockRankingResponse::stockCode)
                .toList();

        List<Stock> existingStocks = stockRepository.findByCodeIn(stockCodes);

        Map<String, Stock> stockMap = existingStocks.stream()
                .collect(Collectors.toMap(Stock::getCode, stock -> stock));

        Set<String> existingStockCodes = stockMap.keySet();

        log.info("API에서 조회된 종목 수: {}, DB에 존재하는 종목 수: {}",
                stocks.size(), existingStockCodes.size());

        return stocks.stream()
                .filter(stock -> existingStockCodes.contains(stock.stockCode()))
                .map(stockDto -> stockDto.withMarketType(
                        stockMap.get(stockDto.stockCode()).getMarketType()))
                .limit(limit)
                .toList();
    }
}
