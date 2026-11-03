package grit.stockIt.domain.stock.analysis.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.stock.analysis.dto.DividendData;
import grit.stockIt.domain.stock.analysis.dto.FinancialData;
import grit.stockIt.domain.stock.analysis.dto.MarketData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisStockAnalysisRepository {

    private static final String MARKET_DATA_KEY_PATTERN = "stock:analysis:market:%s";
    private static final String FINANCIAL_DATA_KEY_PATTERN = "stock:analysis:financial:%s";
    private static final String DIVIDEND_DATA_KEY_PATTERN = "stock:analysis:dividend:%s";
    
    private static final Duration MARKET_DATA_TTL = Duration.ofMinutes(5);  // 5분
    private static final Duration FINANCIAL_DATA_TTL = Duration.ofHours(24);  // 24시간
    private static final Duration DIVIDEND_DATA_TTL = Duration.ofHours(24);   // 24시간

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // 시장 데이터 (시가총액, PER, PBR)
    public void saveMarketData(String stockCode, MarketData data) {
        if (stockCode == null || data == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(
                    buildMarketDataKey(stockCode),
                    json,
                    MARKET_DATA_TTL
            );
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Redis에 시장 데이터 저장 실패. stockCode={}", stockCode, e);
        }
    }

    public Optional<MarketData> getMarketData(String stockCode) {
        if (stockCode == null) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(buildMarketDataKey(stockCode));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, MarketData.class));
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Redis에서 시장 데이터 조회 실패. stockCode={}", stockCode, e);
            return Optional.empty();
        }
    }

    // 재무 데이터 (ROE, 부채비율)
    public void saveFinancialData(String stockCode, FinancialData data) {
        if (stockCode == null || data == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(
                    buildFinancialDataKey(stockCode),
                    json,
                    FINANCIAL_DATA_TTL
            );
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Redis에 재무 데이터 저장 실패. stockCode={}", stockCode, e);
        }
    }

    public Optional<FinancialData> getFinancialData(String stockCode) {
        if (stockCode == null) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(buildFinancialDataKey(stockCode));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, FinancialData.class));
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Redis에서 재무 데이터 조회 실패. stockCode={}", stockCode, e);
            return Optional.empty();
        }
    }

    // 배당 데이터 (배당수익률)
    public void saveDividendData(String stockCode, DividendData data) {
        if (stockCode == null || data == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(data);
            redisTemplate.opsForValue().set(
                    buildDividendDataKey(stockCode),
                    json,
                    DIVIDEND_DATA_TTL
            );
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Redis에 배당 데이터 저장 실패. stockCode={}", stockCode, e);
        }
    }

    public Optional<DividendData> getDividendData(String stockCode) {
        if (stockCode == null) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(buildDividendDataKey(stockCode));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, DividendData.class));
        } catch (DataAccessException | JsonProcessingException e) {
            log.error("Redis에서 배당 데이터 조회 실패. stockCode={}", stockCode, e);
            return Optional.empty();
        }
    }

    private String buildMarketDataKey(String stockCode) {
        return MARKET_DATA_KEY_PATTERN.formatted(stockCode);
    }

    private String buildFinancialDataKey(String stockCode) {
        return FINANCIAL_DATA_KEY_PATTERN.formatted(stockCode);
    }

    private String buildDividendDataKey(String stockCode) {
        return DIVIDEND_DATA_KEY_PATTERN.formatted(stockCode);
    }
}

