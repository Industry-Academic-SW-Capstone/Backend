package grit.stockIt.domain.stock.service;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 차트 기간 타입 정규화·캐시 키·캐시 TTL 판정 순수 로직.
 * Spring 컨텍스트 없이 new로 직접 생성하여 단위 테스트할 수 있다.
 */
@Component
public class ChartPeriodPolicy {

    private static final String CACHE_KEY_PREFIX = "stock:chart:";

    private static final Duration CACHE_TTL_1DAY = Duration.ofMinutes(1);
    private static final Duration CACHE_TTL_1WEEK = Duration.ofMinutes(5);
    private static final Duration CACHE_TTL_3MONTH = Duration.ofMinutes(30);
    private static final Duration CACHE_TTL_1YEAR = Duration.ofHours(1);
    private static final Duration CACHE_TTL_5YEAR = Duration.ofHours(12);

    public String normalize(String periodType) {
        return periodType.toLowerCase();
    }

    public String cacheKey(String stockCode, String normalizedType) {
        return CACHE_KEY_PREFIX + stockCode + ":" + normalizedType;
    }

    /**
     * default 분기는 아래 다섯 판정이 전부 거짓인 기간 타입에서만 도달하는데,
     * 그런 값은 호출 전에 예외로 걸러지므로 실행 경로상 도달하지 않는다.
     */
    public Duration cacheTtl(String normalizedType) {
        return switch (normalizedType) {
            case "1day", "day" -> CACHE_TTL_1DAY;
            case "1week", "week" -> CACHE_TTL_1WEEK;
            case "3month" -> CACHE_TTL_3MONTH;
            case "1year", "year" -> CACHE_TTL_1YEAR;
            case "5year" -> CACHE_TTL_5YEAR;
            default -> CACHE_TTL_3MONTH;
        };
    }

    public boolean isOneDay(String normalizedType) {
        return "1day".equals(normalizedType) || "day".equals(normalizedType);
    }

    public boolean isOneWeek(String normalizedType) {
        return "1week".equals(normalizedType) || "week".equals(normalizedType);
    }

    public boolean isThreeMonth(String normalizedType) {
        return "3month".equals(normalizedType);
    }

    public boolean isOneYear(String normalizedType) {
        return "1year".equals(normalizedType) || "year".equals(normalizedType);
    }

    public boolean isFiveYear(String normalizedType) {
        return "5year".equals(normalizedType);
    }

    public String unsupportedPeriodMessage(String periodType) {
        return "Invalid period type: " + periodType + ". Supported: 1day, 1week, 3month, 1year, 5year";
    }
}
