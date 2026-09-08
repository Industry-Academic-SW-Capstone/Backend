package grit.stockIt.domain.stock.service;

import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.support.IntegrationTestSupport;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 기간 타입별 KIS 요청 프로파일(호출 수·경로·tr_id)을 현재 동작 그대로 고정한다.
 *
 * 이 파일은 리팩터링 오라클의 불변 기준선이다(계획 AC-1). 순수 클래스 추출 커밋들이
 * 이 파일을 수정하지 않았음을 git으로 증명하므로, 여기 적힌 단정은 나중에 강화하거나
 * 완화할 수 없다.
 *
 * 1day는 벽시계에 의존하므로 시각으로 분기하지 않고 유계 단정(0 이상 14 이하)만 둔다.
 * 요청이 실제로 나간 경우에만 경로와 tr_id를 추가로 고정한다.
 *
 * 승인된 결함 a(HTML 삼킴)·b(Infinity 등락률) 지점, 1week 캐시 키의 값·존재,
 * 1week 결과의 공집합 여부는 이 파일이 단정하지 않는다 — StockChartDefectFreezeTest가 소유한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("StockChartService KIS 호출 프로파일 특성화 테스트 (통합 테스트)")
class StockChartCallProfileCharacterizationTest extends IntegrationTestSupport {

    private static final MockWebServer KIS = new MockWebServer();

    private static final String DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String MINUTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice";
    private static final String DAILY_MINUTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";

    private static final String DAILY_TR_ID = "FHKST03010100";
    private static final String MINUTE_TR_ID = "FHKST03010200";
    private static final String DAILY_MINUTE_TR_ID = "FHKST03010230";

    private static final List<String> DAILY_MINUTE_WINDOWS = List.of("153000", "133000", "113000", "093000");

    private static final String STOCK_CODE = "005930";
    private static final List<String> TARGET_KEYS = List.of(
            "stock:chart:005930:1day",
            "stock:chart:005930:1week",
            "stock:chart:005930:3month",
            "stock:chart:005930:1year",
            "stock:chart:005930:5year");

    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WEEK_TIMEOUT = Duration.ofSeconds(120);

    private static final List<KisCall> CALLS = Collections.synchronizedList(new ArrayList<>());

    /**
     * 관측 전용 계수 키. 라우팅에는 쓰지 않는다.
     */
    private record KisCall(String path, String periodDivCode, String date, String hour, String trId) { }

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
                String periodDivCode = url.queryParameter("FID_PERIOD_DIV_CODE");
                String date = url.queryParameter("FID_INPUT_DATE_1");
                String hour = url.queryParameter("FID_INPUT_HOUR_1");
                CALLS.add(new KisCall(path, periodDivCode, date, hour, request.getHeader("tr_id")));

                if (DAILY_PATH.equals(path) && ("D".equals(periodDivCode) || "M".equals(periodDivCode)) && hour == null) {
                    return jsonResponse(dailyEnvelope());
                }
                if (MINUTE_PATH.equals(path) && periodDivCode == null) {
                    return jsonResponse(minuteEnvelope("20260601", hour));
                }
                if (DAILY_MINUTE_PATH.equals(path) && periodDivCode == null && DAILY_MINUTE_WINDOWS.contains(hour)) {
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
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private KisTokenManager kisTokenManager;

    @BeforeAll
    void kisApiUrlMustPointAtMockServer() {
        assertThat(environment.getProperty("kis.api.url")).isEqualTo(mockBaseUrl());
    }

    @BeforeEach
    void setUp() {
        when(kisTokenManager.getAccessToken()).thenReturn("test-token");
        TARGET_KEYS.forEach(redisTemplate::delete);
        CALLS.clear();
    }

    @Test
    @DisplayName("3month는 일봉 경로로 정확히 1회 호출한다")
    void threeMonth_issuesExactlyOneDailyRequest() {
        stockChartService.getStockChart(STOCK_CODE, "3month").block(SHORT_TIMEOUT);

        assertThat(CALLS).hasSize(1);
        assertThat(CALLS.get(0).path()).isEqualTo(DAILY_PATH);
        assertThat(CALLS.get(0).trId()).isEqualTo(DAILY_TR_ID);
        assertThat(CALLS.get(0).periodDivCode()).isEqualTo("D");
    }

    @Test
    @DisplayName("1year는 6개월씩 나눠 일봉 경로로 정확히 2회 호출한다")
    void oneYear_issuesExactlyTwoDailyRequests() {
        stockChartService.getStockChart(STOCK_CODE, "1year").block(SHORT_TIMEOUT);

        assertThat(CALLS).hasSize(2);
        assertThat(CALLS).allSatisfy(call -> {
            assertThat(call.path()).isEqualTo(DAILY_PATH);
            assertThat(call.trId()).isEqualTo(DAILY_TR_ID);
            assertThat(call.periodDivCode()).isEqualTo("D");
        });
    }

    @Test
    @DisplayName("5year는 월봉 경로로 정확히 1회 호출한다")
    void fiveYear_issuesExactlyOneMonthlyRequest() {
        stockChartService.getStockChart(STOCK_CODE, "5year").block(SHORT_TIMEOUT);

        assertThat(CALLS).hasSize(1);
        assertThat(CALLS.get(0).path()).isEqualTo(DAILY_PATH);
        assertThat(CALLS.get(0).trId()).isEqualTo(DAILY_TR_ID);
        assertThat(CALLS.get(0).periodDivCode()).isEqualTo("M");
    }

    @Test
    @DisplayName("1week는 5영업일 × 4윈도 = 일별 분봉 경로로 정확히 20회 호출한다")
    void oneWeek_issuesExactlyTwentyDailyMinuteRequests() {
        stockChartService.getStockChart(STOCK_CODE, "1week").block(WEEK_TIMEOUT);

        assertThat(CALLS).hasSize(20);
        assertThat(CALLS).allSatisfy(call -> {
            assertThat(call.path()).isEqualTo(DAILY_MINUTE_PATH);
            assertThat(call.trId()).isEqualTo(DAILY_MINUTE_TR_ID);
            assertThat(call.periodDivCode()).isNull();
        });

        Map<String, Long> countsByHour = CALLS.stream()
                .collect(Collectors.groupingBy(KisCall::hour, Collectors.counting()));
        assertThat(countsByHour).isEqualTo(Map.of(
                "153000", 5L,
                "133000", 5L,
                "113000", 5L,
                "093000", 5L));

        Map<KisCall, Long> countsByTuple = CALLS.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(countsByTuple).hasSize(20);
        assertThat(countsByTuple.values()).allMatch(count -> count == 1L);
    }

    @Test
    @DisplayName("1day 호출 수는 0 이상 14 이하이며, 호출이 나갔다면 당일 분봉 경로와 tr_id를 쓴다")
    void oneDay_issuesBoundedIntradayRequests() {
        stockChartService.getStockChart(STOCK_CODE, "1day").block(SHORT_TIMEOUT);

        int delta = CALLS.size();
        assertThat(delta).isBetween(0, 14);
        if (delta > 0) {
            assertThat(CALLS).allSatisfy(call -> {
                assertThat(call.path()).isEqualTo(MINUTE_PATH);
                assertThat(call.trId()).isEqualTo(MINUTE_TR_ID);
            });
        }
    }

    private static String cannedContractHour(String requestHour) {
        return switch (requestHour) {
            case "093000" -> "090000";
            case "113000" -> "110000";
            case "133000" -> "130000";
            default -> "150000";
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
