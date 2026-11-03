package grit.stockIt.domain.stock.analysis.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.stock.analysis.dto.*;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRecommendService {

    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final StockAnalysisService stockAnalysisService;
    private final PythonAnalysisClient pythonAnalysisClient;

    @Transactional(readOnly = true)
    public Mono<RecommendResponse> recommend(Long accountId, String email, String persona, int topN) {
        log.info("종목 추천 요청: accountId={}, email={}, persona={}, topN={}", accountId, email, persona, topN);

        // 1. Account 조회 및 권한 확인
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        if (!account.getMember().getEmail().equals(email)) {
            return Mono.error(new ForbiddenException("해당 계좌에 대한 권한이 없습니다."));
        }

        // 2. 보유 종목 조회
        List<AccountStock> accountStocks = accountStockRepository.findByAccountIdWithStock(accountId);

        if (accountStocks.isEmpty()) {
            log.info("보유종목이 없어 추천 불가: accountId={}", accountId);
            return Mono.error(new BadRequestException("보유 종목이 없어 추천을 제공할 수 없습니다."));
        }

        log.info("보유종목 {}개 발견, 재무 데이터 수집 시작: accountId={}", accountStocks.size(), accountId);

        // 3. 각 종목 재무 데이터 병렬 조회
        List<Mono<RecommendStockDto>> stockMonoList = accountStocks.stream()
                .map(accountStock -> {
                    String stockCode = accountStock.getStock().getCode();
                    BigDecimal investmentAmount = accountStock.getAveragePrice()
                            .multiply(BigDecimal.valueOf(accountStock.getQuantity()));

                    return buildRecommendStockDto(stockCode, investmentAmount.doubleValue());
                })
                .toList();

        // 4. 데이터 수집 후 AI 서버 호출
        return Flux.merge(stockMonoList)
                .collectList()
                .flatMap(stocks -> {
                    log.info("{}개 종목 데이터 수집 완료, AI 추천 요청: accountId={}", stocks.size(), accountId);
                    RecommendRequest request = new RecommendRequest(stocks, persona, topN);
                    return pythonAnalysisClient.recommendStocks(request);
                })
                .onErrorResume(e -> {
                    if (e instanceof BadRequestException || e instanceof ForbiddenException) {
                        return Mono.error(e);
                    }
                    log.error("종목 추천 실패: accountId={}", accountId, e);
                    return Mono.error(new RuntimeException("종목 추천 중 오류가 발생했습니다: " + e.getMessage(), e));
                });
    }

    private Mono<RecommendStockDto> buildRecommendStockDto(String stockCode, double investmentAmount) {
        Mono<MarketData> marketDataMono = stockAnalysisService.getMarketData(stockCode);
        Mono<FinancialData> financialDataMono = stockAnalysisService.getFinancialData(stockCode);
        Mono<DividendData> dividendDataMono = stockAnalysisService.getDividendData(stockCode);

        return Mono.zip(marketDataMono, financialDataMono, dividendDataMono)
                .map(tuple -> new RecommendStockDto(
                        stockCode,
                        tuple.getT1().marketCap() != null ? tuple.getT1().marketCap().doubleValue() : null,
                        tuple.getT1().per(),
                        tuple.getT1().pbr(),
                        tuple.getT2().roe(),
                        tuple.getT2().debtRatio(),
                        tuple.getT3().dividendYield() != null ? tuple.getT3().dividendYield() : 0.0,
                        investmentAmount
                ))
                .doOnError(e -> log.error("종목 데이터 수집 실패: stockCode={}", stockCode, e))
                .onErrorResume(e -> {
                    log.warn("종목 데이터 수집 실패, 기본값 사용: stockCode={}", stockCode);
                    return Mono.just(new RecommendStockDto(stockCode, null, null, null, null, null, 0.0, investmentAmount));
                });
    }
}
