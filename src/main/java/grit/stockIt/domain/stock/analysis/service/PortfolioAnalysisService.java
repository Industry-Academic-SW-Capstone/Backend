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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 포트폴리오 분석 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioAnalysisService {

    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final StockAnalysisService stockAnalysisService;
    private final PythonAnalysisClient pythonAnalysisClient;

    @Transactional(readOnly = true)
    public Mono<PortfolioAnalysisResponse> analyzePortfolio(Long accountId) {
        log.info("포트폴리오 분석 요청: accountId={}", accountId);

        // 1. Account 조회 및 권한 확인
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        ensureAccountOwner(account);

        // 2. AccountStock에서 보유 종목 조회
        List<AccountStock> accountStocks = accountStockRepository
                .findByAccountIdWithStock(accountId);

        if (accountStocks.isEmpty()) {
            log.info("보유종목이 없습니다: accountId={}", accountId);
            return Mono.just(createEmptyResponse());
        }

        log.info("보유종목 {}개 발견: accountId={}", accountStocks.size(), accountId);

        // 3. 각 종목의 재무 데이터 조회 (병렬 처리)
        List<Mono<PortfolioStockDto>> stockMonoList = accountStocks.stream()
                .map(accountStock -> {
                    String stockCode = accountStock.getStock().getCode();
                    String stockName = accountStock.getStock().getName();

                    // 투자금액 계산
                    BigDecimal investmentAmount = accountStock.getAveragePrice()
                            .multiply(BigDecimal.valueOf(accountStock.getQuantity()));

                    return getStockDataForPortfolio(stockCode, stockName, investmentAmount);
                })
                .toList();

        // 4. 모든 종목 데이터 수집
        return Flux.merge(stockMonoList)
                .collectList()
                .flatMap(stocks -> {
                    if (stocks.isEmpty()) {
                        log.warn("수집된 종목 데이터가 없습니다: accountId={}", accountId);
                        return Mono.just(createEmptyResponse());
                    }

                    log.info("{}개 종목 데이터 수집 완료, AI 서버로 분석 요청: accountId={}", stocks.size(), accountId);

                    // 5. Python AI 서버로 POST 요청
                    PortfolioAnalysisRequest request = new PortfolioAnalysisRequest(stocks);
                    return pythonAnalysisClient.analyzePortfolio(request);
                })
                .onErrorResume(e -> {
                    log.error("포트폴리오 분석 실패: accountId={}", accountId, e);
                    return Mono.just(createEmptyResponse());
                });
    }

    /**
     * 각 종목의 재무 데이터를 조회하는 헬퍼 메서드
     */
    private Mono<PortfolioStockDto> getStockDataForPortfolio(
            String stockCode,
            String stockName,
            BigDecimal investmentAmount) {

        Mono<MarketData> marketDataMono = stockAnalysisService.getMarketData(stockCode);
        Mono<FinancialData> financialDataMono = stockAnalysisService.getFinancialData(stockCode);
        Mono<DividendData> dividendDataMono = stockAnalysisService.getDividendData(stockCode);

        return Mono.zip(marketDataMono, financialDataMono, dividendDataMono)
                .map(tuple -> {
                    MarketData marketData = tuple.getT1();
                    FinancialData financialData = tuple.getT2();
                    DividendData dividendData = tuple.getT3();

                    return new PortfolioStockDto(
                            stockCode,
                            stockName,  // 종목명 추가
                            marketData.marketCap() != null ? marketData.marketCap().doubleValue() : null,
                            marketData.per(),
                            marketData.pbr(),
                            financialData.roe(),
                            financialData.debtRatio(),
                            dividendData.dividendYield() != null ? dividendData.dividendYield() : 0.0,
                            investmentAmount.doubleValue()
                    );
                })
                .doOnError(e -> log.error("종목 데이터 수집 실패: stockCode={}, stockName={}", stockCode, stockName, e))
                .onErrorResume(e -> {
                    // 에러 발생 시 기본값으로 반환
                    log.warn("종목 데이터 수집 실패, 기본값 사용: stockCode={}, stockName={}", stockCode, stockName);
                    return Mono.just(new PortfolioStockDto(
                            stockCode,
                            stockName,  // 종목명 추가
                            null,
                            null,
                            null,
                            null,
                            null,
                            0.0,
                            investmentAmount.doubleValue()
                    ));
                });
    }

    /**
     * 계좌 소유자 확인
     */
    private void ensureAccountOwner(Account account) {
        String memberEmail = getAuthenticatedEmail();
        if (!account.getMember().getEmail().equals(memberEmail)) {
            throw new ForbiddenException("해당 계좌에 대한 권한이 없습니다.");
        }
    }

    /**
     * 인증된 사용자 이메일 조회
     */
    private String getAuthenticatedEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ForbiddenException("로그인이 필요합니다.");
        }
        return authentication.getName();
    }

    /**
     * 빈 응답 생성
     */
    private PortfolioAnalysisResponse createEmptyResponse() {
        return new PortfolioAnalysisResponse(
                List.of(),
                Map.of(),
                List.of(),
                List.of()
        );
    }
}

