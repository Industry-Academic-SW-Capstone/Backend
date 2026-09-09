package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import com.google.common.util.concurrent.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 랭킹용 현재가 배치 수집
 * - Redis 캐시 우선 조회 → 캐시 미스 시 KIS API 호출 (Rate Limiting 적용)
 */
@Slf4j
@Service
public class RankingPriceCollectionService {

    private final AccountStockRepository accountStockRepository;
    private final RedisMarketDataRepository redisMarketDataRepository;
    private final StockDetailService stockDetailService;

    /**
     * KIS API 호출 속도 제한. KIS가 초당 30건을 막으므로 기본값은 여유를 둔 25건이다.
     *
     * <p>초당 한도를 프로퍼티로 받는 이유: 하드코딩하면 테스트에서 끌 수 없다.
     * 캐시 미스 종목이 N개면 이 리미터가 N/25초 동안 스레드를 재우는데, 테스트 DB에
     * 종목이 쌓일수록 대기가 선형으로 늘어 빌드 시간을 지배하게 된다
     * (실측: 종목 598개 → 테스트 메서드 하나당 약 24초 대기).
     * 테스트 프로파일은 사실상 무제한으로 설정한다.
     */
    private final RateLimiter kisApiRateLimiter;

    public RankingPriceCollectionService(
            AccountStockRepository accountStockRepository,
            RedisMarketDataRepository redisMarketDataRepository,
            StockDetailService stockDetailService,
            @Value("${kis.api.rate-limit-per-second:25.0}") double rateLimitPerSecond) {
        this.accountStockRepository = accountStockRepository;
        this.redisMarketDataRepository = redisMarketDataRepository;
        this.stockDetailService = stockDetailService;
        this.kisApiRateLimiter = RateLimiter.create(rateLimitPerSecond);
    }

    // ==================== 배치 현재가 수집 ====================

    /**
     * 모든 계좌의 보유 종목 코드 수집 (중복 제거)
     * JPQL로 DISTINCT 조회하여 DB 레벨에서 중복 제거
     */
    Set<String> collectAllHeldStockCodes() {
        List<String> stockCodes = accountStockRepository.findDistinctStockCodes();
        return new HashSet<>(stockCodes);
    }

    /**
     * 배치로 종목 현재가 수집
     * 1. Redis 캐시 우선 조회
     * 2. 캐시 미스 시 KIS API 호출 (Rate Limiting 적용)
     * 
     * @param stockCodes 조회할 종목 코드 Set
     * @return Map<종목코드, 현재가>
     */
    Map<String, BigDecimal> batchFetchCurrentPrices(Set<String> stockCodes) {
        Map<String, BigDecimal> prices = new HashMap<>();
        List<String> cacheMissStocks = new ArrayList<>();

        for (String stockCode : stockCodes) {
            Optional<BigDecimal> cachedPrice = redisMarketDataRepository.getLastPrice(stockCode);
            if (cachedPrice.isPresent()) {
                prices.put(stockCode, cachedPrice.get());
            } else {
                cacheMissStocks.add(stockCode);
            }
        }

        log.info("캐시 히트: {}/{} (미스: {}개)", 
                prices.size(), stockCodes.size(), cacheMissStocks.size());

        if (!cacheMissStocks.isEmpty()) {
            fetchPricesWithRateLimit(cacheMissStocks, prices);
        }

        return prices;
    }

    /**
     * Rate Limiting을 적용하여 KIS API에서 현재가 조회
     * - 초당 25개로 제한
     * - 실패 시 로그만 기록하고 계속 진행 (해당 종목은 0원 처리)
     */
    private void fetchPricesWithRateLimit(List<String> stockCodes, Map<String, BigDecimal> prices) {
        int successCount = 0;
        int failCount = 0;

        for (String stockCode : stockCodes) {
            try {
                kisApiRateLimiter.acquire();

                BigDecimal price = stockDetailService.getCurrentPrice(stockCode)
                        .timeout(Duration.ofSeconds(3))
                        .block();

                if (price != null && price.compareTo(BigDecimal.ZERO) > 0) {
                    prices.put(stockCode, price);
                    successCount++;
                } else {
                    log.warn("종목 {} 현재가 조회 실패 (null 또는 0원)", stockCode);
                    prices.put(stockCode, BigDecimal.ZERO);
                    failCount++;
                }

            } catch (Exception e) {
                log.error("종목 {} 현재가 조회 중 예외 발생: {}", stockCode, e.getMessage());
                prices.put(stockCode, BigDecimal.ZERO);
                failCount++;
            }
        }

        log.info("🔄 KIS API 호출 완료 - 성공: {}, 실패: {}", successCount, failCount);
    }
}
