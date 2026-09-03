package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.StockChartResponse;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.support.IntegrationTestSupport;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * StockChartService 특성화 테스트 (리팩토링 전 현재 동작 고정).
 *
 * <p>KIS API는 MockWebServer로 대체한다 — {@code kis.api.url}만 바꿔 끼우면 되므로
 * WebClient 빈은 그대로 쓴다. 토큰 발급은 외부 호출이라 {@link KisTokenManager}만 목으로 둔다.
 *
 * <p>여기서 고정하는 것은 "지금 이렇게 동작한다"이지 "이렇게 동작해야 한다"가 아니다.
 * 의심스러운 동작은 판단을 보류하고 그대로 고정한 뒤, 분해 단계에서 별도로 다룬다.
 */
@DisplayName("StockChartService 특성화 테스트")
class StockChartCharacterizationTest extends IntegrationTestSupport {

    private static final MockWebServer KIS = new MockWebServer();
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String STOCK_CODE = "005930";

    static {
        try {
            KIS.start();
        } catch (IOException e) {
            throw new IllegalStateException("MockWebServer 기동 실패", e);
        }
    }

    @DynamicPropertySource
    static void kisApiUrl(DynamicPropertyRegistry registry) {
        registry.add("kis.api.url", () -> KIS.url("/").toString());
    }

    @AfterAll
    static void shutdownServer() throws IOException {
        KIS.shutdown();
    }

    @Autowired
    private StockChartService stockChartService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private KisTokenManager kisTokenManager;

    @BeforeEach
    void setUp() {
        given(kisTokenManager.getAccessToken()).willReturn("test-access-token");

        Set<String> cacheKeys = redisTemplate.keys("stock:chart:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }
        drainPendingRequests();
    }

    /** 이전 테스트가 큐에 남긴 요청 기록을 비운다 — 호출 횟수 단언이 서로 오염되지 않게. */
    private void drainPendingRequests() {
        try {
            while (KIS.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
                // drain
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 일봉/주봉/월봉 응답(output2). date는 yyyyMMdd. */
    private static void enqueueDailyChart(List<String> dates) {
        String rows = dates.stream()
                .map(d -> """
                        {"stck_bsop_date":"%s","stck_clpr":"70000","stck_oprc":"69000",
                         "stck_hgpr":"71000","stck_lwpr":"68000","acml_vol":"1000",
                         "acml_tr_pbmn":"70000000","prdy_vrss":"1000","prdy_vrss_sign":"2"}
                        """.formatted(d))
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        KIS.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{},"output2":[%s]}
                        """.formatted(rows)));
    }

    /** KIS 응답 픽스처용 yyyyMMdd 문자열. */
    private static String daysAgo(int days) {
        return dateDaysAgo(days).format(YYYYMMDD);
    }

    /** 단언용 — StockChartResponse.date는 LocalDate다. */
    private static LocalDate dateDaysAgo(int days) {
        return LocalDate.now().minusDays(days);
    }

    @Nested
    @DisplayName("캐싱")
    class Caching {

        @Test
        @DisplayName("캐시가 비어 있으면 KIS를 호출하고 결과를 캐시에 저장한다")
        void cacheMiss_callsKisAndStores() throws Exception {
            enqueueDailyChart(List.of(daysAgo(2), daysAgo(1)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).hasSize(2);
            assertThat(KIS.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(redisTemplate.opsForValue().get("stock:chart:" + STOCK_CODE + ":3month")).isNotNull();
        }

        @Test
        @DisplayName("캐시가 있으면 KIS를 호출하지 않는다")
        void cacheHit_skipsKis() throws Exception {
            enqueueDailyChart(List.of(daysAgo(1)));
            stockChartService.getStockChart(STOCK_CODE, "3month").block();
            assertThat(KIS.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

            List<StockChartResponse> cached = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(cached).hasSize(1);
            assertThat(KIS.takeRequest(300, TimeUnit.MILLISECONDS))
                    .as("두 번째 호출은 캐시에서 나와야 한다")
                    .isNull();
        }

        @Test
        @DisplayName("기간 타입마다 캐시 TTL이 다르다")
        void ttl_variesByPeriodType() {
            enqueueDailyChart(List.of(daysAgo(1)));
            stockChartService.getStockChart(STOCK_CODE, "3month").block();
            enqueueDailyChart(List.of(daysAgo(1)));
            stockChartService.getStockChart(STOCK_CODE, "5year").block();

            Long threeMonthTtl = redisTemplate.getExpire("stock:chart:" + STOCK_CODE + ":3month");
            Long fiveYearTtl = redisTemplate.getExpire("stock:chart:" + STOCK_CODE + ":5year");

            assertThat(threeMonthTtl).isLessThanOrEqualTo(Duration.ofMinutes(30).toSeconds());
            assertThat(fiveYearTtl).isGreaterThan(Duration.ofHours(1).toSeconds());
        }

        @Test
        @DisplayName("캐시 내용이 깨져 있으면 예외 없이 KIS 호출로 폴백한다")
        void corruptCache_fallsBackToApi() throws Exception {
            redisTemplate.opsForValue().set("stock:chart:" + STOCK_CODE + ":3month", "not-json");
            enqueueDailyChart(List.of(daysAgo(1)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).hasSize(1);
            assertThat(KIS.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Nested
    @DisplayName("기간 타입 분기")
    class PeriodDispatch {

        @Test
        @DisplayName("지원하지 않는 기간 타입은 IllegalArgumentException이다")
        void unsupportedPeriod_throws() {
            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "10year").block())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid period type");
        }

        @Test
        @DisplayName("기간 타입은 대소문자를 가리지 않는다")
        void periodType_isCaseInsensitive() {
            enqueueDailyChart(List.of(daysAgo(1)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3MONTH").block();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("캐시 키는 정규화된 소문자 기간 타입을 쓴다")
        void cacheKey_usesNormalizedPeriodType() {
            enqueueDailyChart(List.of(daysAgo(1)));

            stockChartService.getStockChart(STOCK_CODE, "3MONTH").block();

            assertThat(redisTemplate.opsForValue().get("stock:chart:" + STOCK_CODE + ":3month")).isNotNull();
        }

        @Test
        @DisplayName("1year는 6개월씩 두 번 나눠 호출한다")
        void oneYear_splitsIntoTwoCalls() throws Exception {
            enqueueDailyChart(List.of(daysAgo(300)));
            enqueueDailyChart(List.of(daysAgo(10)));

            stockChartService.getStockChart(STOCK_CODE, "1year").block();

            assertThat(KIS.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            assertThat(KIS.takeRequest(2, TimeUnit.SECONDS))
                    .as("KIS 기간 제한 때문에 6개월씩 2회 호출한다")
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("정렬·필터링")
    class SortingAndFiltering {

        @Test
        @DisplayName("일봉은 과거에서 현재 순으로 정렬된다")
        void dailyChart_sortedOldestFirst() {
            enqueueDailyChart(List.of(daysAgo(1), daysAgo(10), daysAgo(5)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).extracting(StockChartResponse::date)
                    .containsExactly(dateDaysAgo(10), dateDaysAgo(5), dateDaysAgo(1));
        }

        @Test
        @DisplayName("1year는 7일 간격으로 솎아내되 마지막 데이터는 항상 포함한다")
        void oneYear_thinsToSevenDayGapsButAlwaysKeepsLast() {
            // 하루 간격 데이터를 넣으면 7일 규칙상 첫 항목과 마지막 항목만 남는다.
            enqueueDailyChart(List.of(daysAgo(5), daysAgo(4), daysAgo(3)));
            enqueueDailyChart(List.of(daysAgo(2), daysAgo(1)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1year").block();

            assertThat(result).extracting(StockChartResponse::date)
                    .as("첫 항목 + 마지막 항목(간격과 무관하게 강제 포함)")
                    .containsExactly(dateDaysAgo(5), dateDaysAgo(1));
        }
    }
}
