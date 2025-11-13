package grit.stockIt.domain.matching.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisMarketDataRepository {

    private static final String LAST_PRICE_KEY_PATTERN = "sim:price:last:%s";

    private final StringRedisTemplate redisTemplate;

    public void updateLastPrice(String stockCode, BigDecimal price) {
        if (stockCode == null || price == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(buildLastPriceKey(stockCode), price.toPlainString());
        } catch (DataAccessException e) {
            log.error("Redis에 마지막 체결가 저장 실패. stockCode={}", stockCode, e);
        }
    }

    public Optional<BigDecimal> getLastPrice(String stockCode) {
        if (stockCode == null) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(buildLastPriceKey(stockCode));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(new BigDecimal(value));
        } catch (DataAccessException e) {
            log.error("Redis에서 마지막 체결가 조회 실패. stockCode={}", stockCode, e);
            return Optional.empty();
        } catch (NumberFormatException e) {
            log.warn("Redis 마지막 체결가 값 파싱 실패. stockCode={}", stockCode, e);
            return Optional.empty();
        }
    }

    private String buildLastPriceKey(String stockCode) {
        return LAST_PRICE_KEY_PATTERN.formatted(stockCode);
    }
}

