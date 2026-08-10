package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import com.google.common.util.concurrent.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class RankingPriceCollectionService {

    private final AccountStockRepository accountStockRepository;
    private final RedisMarketDataRepository redisMarketDataRepository;
    private final StockDetailService stockDetailService;

    // Rate Limiter: KIS API 초당 30개 제한 (안전하게 25개로 설정)
    private final RateLimiter kisApiRateLimiter = RateLimiter.create(25.0);

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

        // 1단계: Redis 캐시에서 조회
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

        // 2단계: 캐시 미스 종목만 KIS API 호출 (Rate Limiting)
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
                // Rate Limiter 적용 (초당 25개 제한)
                kisApiRateLimiter.acquire();

                // StockDetailService의 getCurrentPrice 호출 (비동기 → 동기 변환)
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
