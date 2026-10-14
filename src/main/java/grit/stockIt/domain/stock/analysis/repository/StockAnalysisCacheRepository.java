package grit.stockIt.domain.stock.analysis.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.stock.analysis.dto.DividendData;
import grit.stockIt.domain.stock.analysis.dto.FinancialData;
import grit.stockIt.domain.stock.analysis.dto.MarketData;
import grit.stockIt.global.cache.LocalTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class StockAnalysisCacheRepository {

    private static final String MARKET_DATA_KEY_PATTERN = "stock:analysis:market:%s";
    private static final String FINANCIAL_DATA_KEY_PATTERN = "stock:analysis:financial:%s";
    private static final String DIVIDEND_DATA_KEY_PATTERN = "stock:analysis:dividend:%s";

    private static final Duration MARKET_DATA_TTL = Duration.ofMinutes(5);  // 5분
    private static final Duration FINANCIAL_DATA_TTL = Duration.ofHours(24);  // 24시간
    private static final Duration DIVIDEND_DATA_TTL = Duration.ofHours(24);   // 24시간

    private final LocalTtlCache cache;
    private final ObjectMapper objectMapper;

    // 시장 데이터 (시가총액, PER, PBR)
    public void saveMarketData(String stockCode, MarketData data) {
        save(MARKET_DATA_KEY_PATTERN, stockCode, data, MARKET_DATA_TTL);
    }

    public Optional<MarketData> getMarketData(String stockCode) {
        return find(MARKET_DATA_KEY_PATTERN, stockCode, MarketData.class);
    }

    // 재무 데이터 (ROE, 부채비율)
    public void saveFinancialData(String stockCode, FinancialData data) {
        save(FINANCIAL_DATA_KEY_PATTERN, stockCode, data, FINANCIAL_DATA_TTL);
    }

    public Optional<FinancialData> getFinancialData(String stockCode) {
        return find(FINANCIAL_DATA_KEY_PATTERN, stockCode, FinancialData.class);
    }

    // 배당 데이터 (배당수익률)
    public void saveDividendData(String stockCode, DividendData data) {
        save(DIVIDEND_DATA_KEY_PATTERN, stockCode, data, DIVIDEND_DATA_TTL);
    }

    public Optional<DividendData> getDividendData(String stockCode) {
        return find(DIVIDEND_DATA_KEY_PATTERN, stockCode, DividendData.class);
    }

    private void save(String keyPattern, String stockCode, Object data, Duration ttl) {
        if (stockCode == null || data == null) {
            return;
        }
        try {
            cache.put(keyPattern.formatted(stockCode), objectMapper.writeValueAsString(data), ttl);
        } catch (JsonProcessingException e) {
            log.error("분석 데이터 캐시 저장 실패. key={}", keyPattern.formatted(stockCode), e);
        }
    }

    private <T> Optional<T> find(String keyPattern, String stockCode, Class<T> type) {
        if (stockCode == null) {
            return Optional.empty();
        }
        String key = keyPattern.formatted(stockCode);
        Optional<String> json = cache.get(key);
        if (json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json.get(), type));
        } catch (JsonProcessingException e) {
            log.error("분석 데이터 캐시 조회 실패. key={}", key, e);
            return Optional.empty();
        }
    }
}
