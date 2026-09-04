package grit.stockIt.domain.stock.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import grit.stockIt.domain.stock.dto.StockChartResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.when;

/**
 * 승인된 결함 a(HTML 응답 삼킴)·b(분모 0 등락률 Infinity)의 현재 동작을 기록한다.
 *
 * 이 파일은 계획 AC-1의 불변 목록이 아니다. 결함 수정 커밋이 여기의 기대값을 갱신하며,
 * 그 diff가 사용자 영향 명세가 된다. 파일을 삭제하거나 이름을 바꾸지 않는다.
 *
 * DF-0는 대조군이다. HTML을 하나도 주입하지 않은 실행이 비어 있지 않은 20건 차트를
 * 돌려주지 못하면 나머지 결함 단정은 공허하게 통과한 것이므로 증거로 인정하지 않는다.
 *
 * OY-1은 결함 동결이 아니라 1year 경로의 내용 오라클이다. 불변 2파일(AC-1)은 호출 수·캐시만
 * 고정하므로 이번 사이클 유일의 비축자 변환인 7일 표본 배선을 아무도 관측하지 않는다.
 * 불변 파일에 넣을 수 없어 갱신 가능한 이 파일이 소유한다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("StockChartService 승인 결함 동결 테스트 (통합 테스트)")
class StockChartDefectFreezeTest extends IntegrationTestSupport {

    private static final MockWebServer KIS = new MockWebServer();

    private static final String DAILY_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String DAILY_MINUTE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";

    private static final List<String> DAILY_MINUTE_WINDOWS = List.of("153000", "133000", "113000", "093000");
    private static final String HTML_BODY = "<html><body>login</body></html>";

    private static final String STOCK_CODE = "005930";
    private static final String KEY_1WEEK = "stock:chart:005930:1week";
    private static final String KEY_3MONTH = "stock:chart:005930:3month";
    private static final String KEY_1YEAR = "stock:chart:005930:1year";
    private static final List<String> TARGET_KEYS = List.of(KEY_1WEEK, KEY_3MONTH, KEY_1YEAR);

    /**
     * 1year 전반기·후반기 응답. 각 응답은 KIS 실제 응답처럼 내림차순이며, 두 절반에 날짜를
     * 일부러 교차 배치했다. 이래야 병합 직후 리스트(allData)가 어떤 도착 순서에서도
     * 정렬 결과와 달라져, 표본 인덱스를 정렬 전 리스트에 적용하는 배선 사고가 드러난다.
     */
    private static final List<String> ONE_YEAR_FIRST_HALF_DATES =
            List.of("20250120", "20250108", "20250106");
    private static final List<String> ONE_YEAR_SECOND_HALF_DATES =
            List.of("20250122", "20250121", "20250113", "20250107");

    private static final String SERVICE_LOGGER_NAME = "grit.stockIt.domain.stock.service.StockChartService";
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WEEK_TIMEOUT = Duration.ofSeconds(120);

    private static final List<KisCall> CALLS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger DAILY_MINUTE_ORDINAL = new AtomicInteger();
    private static final Set<Integer> HTML_ORDINALS = Collections.synchronizedSet(new LinkedHashSet<>());

    private static volatile boolean blankRtCd;
    private static volatile boolean oneYearMode;
    private static volatile String dailyClosePrice = "11000";
    private static volatile String dailyChangeAmount = "1000";

    /**
     * 관측 전용 계수 키. 라우팅에는 쓰지 않는다.
     */
    private record KisCall(String path, String periodDivCode, String date, String hour) { }

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
                CALLS.add(new KisCall(path, periodDivCode, date, hour));

                if (DAILY_PATH.equals(path) && "D".equals(periodDivCode) && hour == null) {
                    if (oneYearMode) {
                        return jsonResponse(oneYearEnvelope(date));
                    }
                    return jsonResponse(dailyEnvelope());
                }
                if (DAILY_MINUTE_PATH.equals(path) && periodDivCode == null && DAILY_MINUTE_WINDOWS.contains(hour)) {
                    int ordinal = DAILY_MINUTE_ORDINAL.getAndIncrement();
                    if (HTML_ORDINALS.contains(ordinal)) {
                        return new MockResponse()
                                .setResponseCode(200)
                                .setHeader("Content-Type", "text/html; charset=utf-8")
                                .setBody(HTML_BODY);
                    }
                    if (blankRtCd) {
                        return jsonResponse(blankRtCdEnvelope());
                    }
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
        DAILY_MINUTE_ORDINAL.set(0);
        HTML_ORDINALS.clear();
        blankRtCd = false;
        oneYearMode = false;
        dailyClosePrice = "11000";
        dailyChangeAmount = "1000";
    }

    @Test
    @DisplayName("DF-0 대조군: HTML을 주입하지 않으면 1week 차트가 20건으로 채워진다")
    void df0_withoutHtmlInjection_chartIsNotEmpty() {
        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "1week").block(WEEK_TIMEOUT);

        assertThat(chart).isNotEmpty();
        assertThat(chart).hasSize(20);
        assertThat(dailyMinuteCallCount()).isEqualTo(20);
    }

    @ParameterizedTest(name = "서수 {0} (영업일 {1}번째 / 윈도 {2})")
    @CsvSource({
            "0, 1, 153000",
            "10, 3, 113000",
            "19, 5, 093000"
    })
    @DisplayName("DF-1 결함 a: 부분 HTML 응답은 예외로 전파되고 남은 요청은 발행되지 않는다")
    void df1_partialHtmlResponsePropagatesError(int htmlOrdinal, int businessDayNumber, String window) {
        HTML_ORDINALS.add(htmlOrdinal);
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);

        try {
            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "1week").block(WEEK_TIMEOUT))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("주식 분봉 데이터 조회 실패: " + STOCK_CODE);
            assertThat(dailyMinuteCallCount()).isEqualTo(htmlOrdinal + 1L);

            List<KisCall> dailyMinuteCalls = dailyMinuteCalls();
            assertThat(dailyMinuteCalls.get(htmlOrdinal).hour()).isEqualTo(window);
            assertThat(dailyMinuteCalls.get(htmlOrdinal).date())
                    .isEqualTo(distinctRequestDates(dailyMinuteCalls).get(businessDayNumber - 1));

            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .filter(event -> event.getFormattedMessage().startsWith("KIS 일별 분봉 응답이 HTML입니다"))
                    .count()).isEqualTo(1L);
        } finally {
            serviceLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("DF-2 결함 a 2차 영향: 전 윈도 HTML이면 예외가 전파되고 빈 차트가 캐시되지 않는다")
    void df2_allHtmlResponsesProduceNoCachedEmptyChart() {
        for (int ordinal = 0; ordinal < 20; ordinal++) {
            HTML_ORDINALS.add(ordinal);
        }

        assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "1week").block(WEEK_TIMEOUT))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("주식 분봉 데이터 조회 실패: " + STOCK_CODE);
        assertThat(redisTemplate.hasKey(KEY_1WEEK)).isFalse();
    }

    @Test
    @DisplayName("DF-4 결함 b: 종가와 전일대비가 같으면 등락률이 문자열 0으로 폴백된다")
    void df4_zeroDenominatorFallsBackToZeroChangeRate() {
        dailyClosePrice = "100";
        dailyChangeAmount = "100";

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "3month").block(SHORT_TIMEOUT);

        assertThat(chart).hasSize(1);
        assertThat(chart.get(0).changeRate()).isEqualTo("0");
    }

    @Test
    @DisplayName("DF-5 대조군: 분모가 0이 아니면 등락률은 소수점 두 자리 문자열이다")
    void df5_nonZeroDenominatorProducesFormattedChangeRate() {
        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "3month").block(SHORT_TIMEOUT);

        assertThat(chart).hasSize(1);
        assertThat(chart.get(0).changeRate()).isEqualTo("10.00");
    }

    @Test
    @DisplayName("DF-7 영구 동결: rt_cd가 공백인 일별 분봉 응답은 빈 결과로 삼켜진다")
    void df7_blankResponseCodeIsSwallowed() {
        blankRtCd = true;

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "1week").block(WEEK_TIMEOUT);

        assertThat(chart).isEmpty();
        assertThat(dailyMinuteCallCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("OY-1 1year 내용 오라클: 전역 정렬된 리스트에서 7일 간격 표본이 오름차순으로 방출된다")
    void oy1_oneYearChartEmitsWeeklySamplesFromGloballySortedList() {
        oneYearMode = true;

        List<StockChartResponse> chart = stockChartService.getStockChart(STOCK_CODE, "1year").block(SHORT_TIMEOUT);

        assertThat(chart)
                .extracting(StockChartResponse::date, StockChartResponse::closePrice)
                .containsExactly(
                        tuple(LocalDate.of(2025, 1, 6), 10006),
                        tuple(LocalDate.of(2025, 1, 13), 10013),
                        tuple(LocalDate.of(2025, 1, 20), 10020),
                        tuple(LocalDate.of(2025, 1, 22), 10022));
    }

    private static List<KisCall> dailyMinuteCalls() {
        synchronized (CALLS) {
            return CALLS.stream()
                    .filter(call -> DAILY_MINUTE_PATH.equals(call.path()))
                    .toList();
        }
    }

    private static long dailyMinuteCallCount() {
        return dailyMinuteCalls().size();
    }

    private static List<String> distinctRequestDates(List<KisCall> calls) {
        return calls.stream()
                .map(KisCall::date)
                .distinct()
                .collect(Collectors.toList());
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
                  {"stck_bsop_date":"20260601","stck_clpr":"%s","stck_oprc":"10900",
                   "stck_hgpr":"11100","stck_lwpr":"10800","acml_vol":"100",
                   "acml_tr_pbmn":"1100000","prdy_vrss":"%s","prdy_vrss_sign":"2","prdy_ctrt":""}]}
                """.formatted(dailyClosePrice, dailyChangeAmount);
    }

    /**
     * 1년 구간은 6개월씩 두 번 요청된다. 두 요청의 시작일은 now-12m과 now-6m+1d이므로
     * 그 중간인 now-9m을 기준으로 가르면 어느 쪽도 3개월 여유를 갖는다.
     */
    private static String oneYearEnvelope(String requestStartDate) {
        LocalDate start = LocalDate.parse(requestStartDate, DateTimeFormatter.BASIC_ISO_DATE);
        List<String> dates = start.isBefore(LocalDate.now().minusMonths(9))
                ? ONE_YEAR_FIRST_HALF_DATES
                : ONE_YEAR_SECOND_HALF_DATES;

        String rows = dates.stream()
                .map(StockChartDefectFreezeTest::dailyRow)
                .collect(Collectors.joining(","));
        return "{\"rt_cd\":\"0\",\"msg_cd\":\"MCA00000\",\"msg1\":\"정상처리\",\"output2\":[" + rows + "]}";
    }

    private static String dailyRow(String date) {
        int closePrice = 10000 + Integer.parseInt(date.substring(6));
        return """
                {"stck_bsop_date":"%s","stck_clpr":"%d","stck_oprc":"10900",
                 "stck_hgpr":"11100","stck_lwpr":"10800","acml_vol":"100",
                 "acml_tr_pbmn":"1100000","prdy_vrss":"1000","prdy_vrss_sign":"2","prdy_ctrt":""}
                """.formatted(date, closePrice);
    }

    private static String minuteEnvelope(String date, String hour) {
        return """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리","output2":[
                  {"stck_bsop_date":"%s","stck_cntg_hour":"%s","stck_prpr":"70000",
                   "stck_oprc":"69900","stck_hgpr":"70100","stck_lwpr":"69800",
                   "cntg_vol":"1200","acml_tr_pbmn":"84000000"}]}
                """.formatted(date, hour);
    }

    private static String blankRtCdEnvelope() {
        return """
                {"rt_cd":"","msg_cd":"MCA00000","msg1":"정상처리","output2":[]}
                """;
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
