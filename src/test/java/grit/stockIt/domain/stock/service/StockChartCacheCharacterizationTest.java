package grit.stockIt.domain.stock.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.stock.dto.StockChartResponse;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.support.IntegrationTestSupport;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockChartService의 Redis 캐시 계약과 미지원 기간 타입 예외를 현재 동작 그대로 고정한다.
 *
 * 이 파일은 리팩터링 오라클의 불변 기준선이다(계획 AC-1). 순수 클래스 추출 커밋들이
 * 이 파일을 수정하지 않았음을 git으로 증명하므로, 여기 적힌 단정은 나중에 강화하거나
 * 완화할 수 없다.
 *
 * 승인된 결함 a(HTML 삼킴)·b(Infinity 등락률) 지점, 1week 캐시 키의 값·존재,
 * 1week 결과의 공집합 여부는 이 파일이 단정하지 않는다 — StockChartDefectFreezeTest가 소유한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("StockChartService 캐시·예외 특성화 테스트 (통합 테스트)")
class StockChartCacheCharacterizationTest extends IntegrationTestSupport {

    private static final MockWebServer KIS = new MockWebServer();

    private static final String DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String MINUTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String DAILY_MINUTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";

    private static final String STOCK_CODE = "005930";
    private static final String KEY_1DAY = "stock:chart:005930:1day";
    private static final String KEY_1WEEK = "stock:chart:005930:1week";
    private static final String KEY_3MONTH = "stock:chart:005930:3month";
    private static final String KEY_1YEAR = "stock:chart:005930:1year";
    private static final String KEY_5YEAR = "stock:chart:005930:5year";
    private static final String KEY_FOO = "stock:chart:005930:foo";
    private static final List<String> TARGET_KEYS =
            List.of(KEY_1DAY, KEY_1WEEK, KEY_3MONTH, KEY_1YEAR, KEY_5YEAR, KEY_FOO);

    private static final String SERVICE_LOGGER_NAME = "grit.stockIt.domain.stock.service.StockChartService";
    private static final String INVALID_PERIOD_MESSAGE =
            "Invalid period type: foo. Supported: 1day, 1week, 3month, 1year, 5year";

    private static final String CANNED_DATE = "20260601";
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WEEK_TIMEOUT = Duration.ofSeconds(120);

    static {
        try {
            KIS.start();
        } catch (IOException e) {
            throw new IllegalStateException("MockWebServer 기동 실패", e);
        }
        KIS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                HttpUrl url = request.getRequestUrl();
                if (url == null) {
                    return new MockResponse().setResponseCode(404);
                }
                String path = url.encodedPath();
                if (DAILY_PATH.equals(path)) {
                    return jsonResponse(dailyEnvelope());
                }
                if (MINUTE_PATH.equals(path)) {
                    return jsonResponse(minuteEnvelope(CANNED_DATE, url.queryParameter("FID_INPUT_HOUR_1")));
                }
                if (DAILY_MINUTE_PATH.equals(path)) {
                    String date = url.queryParameter("FID_INPUT_DATE_1");
                    String hour = url.queryParameter("FID_INPUT_HOUR_1");
                    return jsonResponse(minuteEnvelope(date, cannedContractHour(hour)));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
    }

    @DynamicPropertySource
    static void kisApiUrl(DynamicPropertyRegistry registry) {
        registry.add("kis.api.url", () -> mockBaseUrl());
    }

    @Autowired
    private StockChartService stockChartService;

    @Autowired
    private Environment environment;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KisTokenManager kisTokenManager;

    @MockitoSpyBean
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void kisApiUrlMustPointAtMockServer() {
        assertThat(environment.getProperty("kis.api.url")).isEqualTo(mockBaseUrl());
    }

    @BeforeEach
    void setUp() {
        when(kisTokenManager.getAccessToken()).thenReturn("test-token");
        TARGET_KEYS.forEach(redisTemplate::delete);
        reset(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        reset(redisTemplate);
    }

    @Test
    @DisplayName("어서션 1: 캐시 키는 stock:chart:{종목코드}:{소문자 기간타입} 형식이다")
    void cacheKey_isPrefixedStockCodeAndLowerCasedPeriodType() {
        stockChartService.getStockChart(STOCK_CODE, "3MONTH").block(SHORT_TIMEOUT);

        assertThat(redisTemplate.hasKey(KEY_3MONTH)).isTrue();
    }

    @Test
    @DisplayName("어서션 2-a: 1day TTL은 60초다")
    void cacheTtl_oneDay_is60Seconds() {
        stockChartService.getStockChart(STOCK_CODE, "1day").block(SHORT_TIMEOUT);

        assertThat(redisTemplate.getExpire(KEY_1DAY)).isBetween(55L, 60L);
    }

    @Test
    @DisplayName("어서션 2-b: 1week TTL은 300초다")
    void cacheTtl_oneWeek_is300Seconds() {
        stockChartService.getStockChart(STOCK_CODE, "1week").block(WEEK_TIMEOUT);

        assertThat(redisTemplate.getExpire(KEY_1WEEK)).isBetween(295L, 300L);
    }

    @Test
    @DisplayName("어서션 2-c: 3month TTL은 1800초다")
    void cacheTtl_threeMonth_is1800Seconds() {
        stockChartService.getStockChart(STOCK_CODE, "3month").block(SHORT_TIMEOUT);

        assertThat(redisTemplate.getExpire(KEY_3MONTH)).isBetween(1795L, 1800L);
    }

    @Test
    @DisplayName("어서션 2-d: 1year TTL은 3600초다")
    void cacheTtl_oneYear_is3600Seconds() {
        stockChartService.getStockChart(STOCK_CODE, "1year").block(SHORT_TIMEOUT);

        assertThat(redisTemplate.getExpire(KEY_1YEAR)).isBetween(3595L, 3600L);
    }

    @Test
    @DisplayName("어서션 2-e: 5year TTL은 43200초다")
    void cacheTtl_fiveYear_is43200Seconds() {
        stockChartService.getStockChart(STOCK_CODE, "5year").block(SHORT_TIMEOUT);

        assertThat(redisTemplate.getExpire(KEY_5YEAR)).isBetween(43195L, 43200L);
    }

    @Test
    @DisplayName("어서션 3·4: 미지원 기간 타입은 Mono 없이 즉시 IllegalArgumentException을 던지고 메시지는 소문자 입력을 반향한다")
    void unsupportedPeriodType_throwsSynchronouslyWithLowerCasedMessage() {
        assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_PERIOD_MESSAGE);

        assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID_PERIOD_MESSAGE);
    }

    @Test
    @DisplayName("어서션 5: 미지원 기간 타입도 throw보다 Redis GET이 먼저 일어난다 — 캐시가 있으면 예외 없이 캐시 값을 돌려준다")
    void redisGetPrecedesThrow_cachedValueIsReturnedForUnsupportedPeriodType() throws Exception {
        List<StockChartResponse> seeded = List.of(sampleResponse("foo"));
        redisTemplate.opsForValue().set(KEY_FOO, objectMapper.writeValueAsString(seeded), Duration.ofMinutes(5));

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "FOO").block(SHORT_TIMEOUT);

        assertThat(chart).isEqualTo(seeded);
    }

    @Test
    @DisplayName("어서션 5-b: 깨진 캐시 값이면 파싱 실패 WARN 로그가 남은 뒤 같은 경로에서 IllegalArgumentException이 나온다")
    void corruptCacheOnUnsupportedPeriodType_logsParseWarnBeforeThrow() {
        redisTemplate.opsForValue().set(KEY_FOO, "not-json", Duration.ofMinutes(5));
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        try {
            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "FOO"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(INVALID_PERIOD_MESSAGE);

            assertThat(appender.list)
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage())
                                .isEqualTo("캐시 데이터 파싱 실패, API 호출로 대체: stock:chart:005930:foo");
                    });
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("어서션 6: 깨진 캐시 값은 API 호출로 폴백하며 캐시 삭제를 발행하지 않는다")
    void corruptCache_fallsBackToApiWithoutDeletingKey() {
        redisTemplate.opsForValue().set(KEY_3MONTH, "not-json", Duration.ofMinutes(5));
        reset(redisTemplate);

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "3MONTH").block(SHORT_TIMEOUT);

        assertThat(chart).isNotNull();
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("어서션 7: 캐시 저장 실패는 삼켜지고 응답은 그대로 방출된다")
    @SuppressWarnings("unchecked")
    void cacheWriteFailure_isSwallowedAndResponseStillEmits() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doReturn(ops).when(redisTemplate).opsForValue();
        doReturn(null).when(ops).get(anyString());
        doThrow(new RuntimeException("boom")).when(ops).set(anyString(), anyString(), any(Duration.class));

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "3MONTH").block(SHORT_TIMEOUT);

        assertThat(chart).isNotNull();
    }

    @Test
    @DisplayName("어서션 8: 캐시 히트면 KIS 요청이 한 건도 나가지 않는다")
    void cacheHit_issuesZeroKisRequests() throws Exception {
        List<StockChartResponse> seeded = List.of(sampleResponse("3month"));
        redisTemplate.opsForValue().set(KEY_3MONTH, objectMapper.writeValueAsString(seeded), Duration.ofMinutes(5));
        int before = KIS.getRequestCount();

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "3MONTH").block(SHORT_TIMEOUT);

        assertThat(chart).isEqualTo(seeded);
        assertThat(KIS.getRequestCount() - before).isZero();
    }

    @Test
    @DisplayName("어서션 9: 저장된 JSON은 방출된 리스트와 동등하게 역직렬화된다")
    void storedJson_roundTripsToEqualList() throws Exception {
        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "3MONTH").block(SHORT_TIMEOUT);

        String stored = redisTemplate.opsForValue().get(KEY_3MONTH);
        List<StockChartResponse> parsed = objectMapper.readValue(stored, new TypeReference<List<StockChartResponse>>() { });

        assertThat(parsed).isEqualTo(chart);
    }

    private static StockChartResponse sampleResponse(String periodType) {
        return new StockChartResponse(
                STOCK_CODE,
                periodType,
                LocalDate.of(2026, 6, 1),
                LocalTime.of(9, 0),
                10900,
                11100,
                10800,
                11000,
                100L,
                1100000L,
                1000,
                "10.00"
        );
    }

    private static String cannedContractHour(String requestHour) {
        return switch (requestHour == null ? "" : requestHour) {
            case "093000" -> "090000";
            case "113000" -> "110000";
            case "133000" -> "130000";
            case "153000" -> "150000";
            default -> "090000";
        };
    }

    private static String dailyEnvelope() {
        return """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리","output2":[
                  {"stck_bsop_date":"20260601","stck_clpr":"11000","stck_oprc":"10900",
                   "stck_hgpr":"11100","stck_lwpr":"10800","acml_vol":"100",
                   "acml_tr_pbmn":"1100000","prdy_vrss":"1000","prdy_vrss_sign":"2","prdy_ctrt":""}]}
                """;
    }

    private static String minuteEnvelope(String date, String hour) {
        return """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리","output2":[
                  {"stck_bsop_date":"%s","stck_cntg_hour":"%s","stck_prpr":"70000",
                   "stck_oprc":"69900","stck_hgpr":"70100","stck_lwpr":"69800",
                   "cntg_vol":"1200","acml_tr_pbmn":"84000000"}]}
                """.formatted(date, hour);
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(body);
    }

    private static String mockBaseUrl() {
        return "http://localhost:" + KIS.getPort();
    }
}
