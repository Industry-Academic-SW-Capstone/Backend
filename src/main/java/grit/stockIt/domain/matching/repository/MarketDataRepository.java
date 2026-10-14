package grit.stockIt.domain.matching.repository;

import grit.stockIt.global.cache.LocalTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class MarketDataRepository {

    private static final String LAST_PRICE_KEY_PATTERN = "sim:price:last:%s";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5); // 5분 캐시

    private static final String UPPER_LIMIT_PRICE_KEY_PATTERN = "sim:price:upperlimit:%s";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LocalTtlCache cache;

    public void updateLastPrice(String stockCode, BigDecimal price) {
        if (stockCode == null || price == null) {
            return;
        }
        cache.put(buildLastPriceKey(stockCode), price.toPlainString(), CACHE_TTL);
    }

    public Optional<BigDecimal> getLastPrice(String stockCode) {
        if (stockCode == null) {
            return Optional.empty();
        }
        return cache.get(buildLastPriceKey(stockCode)).flatMap(value -> parse(value, stockCode, "마지막 체결가"));
    }

    public void updateUpperLimitPrice(String stockCode, BigDecimal price) {
        if (stockCode == null || price == null) {
            return;
        }
        cache.put(buildUpperLimitPriceKey(stockCode), price.toPlainString(), ttlUntilNextTradingDay());
    }

    public Optional<BigDecimal> getUpperLimitPrice(String stockCode) {
        if (stockCode == null) {
            return Optional.empty();
        }
        return cache.get(buildUpperLimitPriceKey(stockCode)).flatMap(value -> parse(value, stockCode, "상한가"));
    }

    private Optional<BigDecimal> parse(String value, String stockCode, String label) {
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            log.warn("{} 캐시 값 파싱 실패. stockCode={}", label, stockCode, e);
            return Optional.empty();
        }
    }

    private String buildLastPriceKey(String stockCode) {
        return LAST_PRICE_KEY_PATTERN.formatted(stockCode);
    }

    private String buildUpperLimitPriceKey(String stockCode) {
        return UPPER_LIMIT_PRICE_KEY_PATTERN.formatted(stockCode);
    }

    // 상한가는 기준가에서 나오므로 매매일이 바뀌면 값이 달라진다. 자정을 넘긴 값을 쓰면
    // 어제 기준으로 홀딩을 잡게 되므로 날짜 경계에서 반드시 버린다.
    private Duration ttlUntilNextTradingDay() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(KST));
    }
}
