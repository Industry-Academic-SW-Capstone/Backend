package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.StockChartResponse;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.support.IntegrationTestSupport;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.QueueDispatcher;
import okhttp3.mockwebserver.RecordedRequest;
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
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 기본 기준 시각: 2026-09-04(금) 16:00 KST — 장 마감 후.
     * 분봉 조회가 09:00~15:30 전 구간을 훑는 시각이라 호출 횟수가 고정된다.
     */
    private static final ZonedDateTime FIXED_NOW = ZonedDateTime.of(2026, 9, 4, 16, 0, 0, 0, SEOUL);

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

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        given(kisTokenManager.getAccessToken()).willReturn("test-access-token");
        fixClockAt(FIXED_NOW);

        Set<String> cacheKeys = redisTemplate.keys("stock:chart:*");
        if (cacheKeys != null && !cacheKeys.isEmpty()) {
            redisTemplate.delete(cacheKeys);
        }

        // 큐를 새로 갈아끼워 이전 테스트가 남긴 응답·디스패처가 새지 않게 한다.
        KIS.setDispatcher(new QueueDispatcher());
        drainPendingRequests();
    }

    private void fixClockAt(ZonedDateTime moment) {
        given(clock.instant()).willReturn(moment.toInstant());
        given(clock.getZone()).willReturn(SEOUL);
    }

    private void fixClockAt(int year, int month, int day, int hour, int minute) {
        fixClockAt(ZonedDateTime.of(year, month, day, hour, minute, 0, 0, SEOUL));
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

    /** output2 자리에 임의 JSON을 넣어 파싱 분기를 직접 겨냥한다. */
    private static void enqueueRawOutput2(String output2Json) {
        KIS.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{},"output2":%s}
                        """.formatted(output2Json)));
    }

    private static void enqueueBody(String body) {
        KIS.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody(body));
    }

    /** 한 건짜리 일봉 항목. 지정한 필드만 덮어쓴다. */
    private static String dailyRow(String date, String extraFields) {
        return """
                {"stck_bsop_date":"%s","stck_clpr":"70000","stck_oprc":"69000",
                 "stck_hgpr":"71000","stck_lwpr":"68000","acml_vol":"1000",
                 "acml_tr_pbmn":"70000000"%s}
                """.formatted(date, extraFields.isEmpty() ? "" : "," + extraFields);
    }

    /** KIS 응답 픽스처용 yyyyMMdd 문자열. 고정 시계 기준이라 자정을 넘겨도 흔들리지 않는다. */
    private static String daysAgo(int days) {
        return dateDaysAgo(days).format(YYYYMMDD);
    }

    /** 단언용 — StockChartResponse.date는 LocalDate다. */
    private static LocalDate dateDaysAgo(int days) {
        return FIXED_NOW.toLocalDate().minusDays(days);
    }

    /** 분봉 한 건. */
    private static String minuteRow(String date, String hhmmss) {
        return """
                {"stck_bsop_date":"%s","stck_cntg_hour":"%s","stck_prpr":"70500",
                 "stck_oprc":"70000","stck_hgpr":"70800","stck_lwpr":"69900",
                 "cntg_vol":"120","acml_tr_pbmn":"8460000"}
                """.formatted(date, hhmmss);
    }

    private static String minuteBody(String... rows) {
        return """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{},"output2":[%s]}
                """.formatted(String.join(",", rows));
    }

    /** 모든 요청에 같은 본문을 돌려준다 — 호출 횟수가 시각에 따라 달라지는 경로용. */
    private static void respondAlways(String body) {
        KIS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
            }
        });
    }

    /** 요청의 FID_INPUT_DATE_1 값에 맞춰 그 날짜의 분봉을 돌려준다. */
    private static void respondPerRequestedDate(String... timesOfDay) {
        KIS.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String date = request.getRequestUrl() == null
                        ? null
                        : request.getRequestUrl().queryParameter("FID_INPUT_DATE_1");
                String[] rows = new String[timesOfDay.length];
                for (int i = 0; i < timesOfDay.length; i++) {
                    rows[i] = minuteRow(date, timesOfDay[i]);
                }
                return new MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setBody(minuteBody(rows));
            }
        });
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
        @DisplayName("접미사 없는 별칭도 같은 경로를 탄다 (year → 1year)")
        void aliasPeriodType_year() {
            enqueueDailyChart(List.of(daysAgo(300)));
            enqueueDailyChart(List.of(daysAgo(10)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "year").block();

            assertThat(result).isNotEmpty();
            assertThat(redisTemplate.opsForValue().get("stock:chart:" + STOCK_CODE + ":year")).isNotNull();
        }

        @Test
        @DisplayName("접미사 없는 별칭도 같은 경로를 탄다 (day → 1day)")
        void aliasPeriodType_day() {
            respondAlways(minuteBody(minuteRow("20260904", "090000")));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "day").block();

            assertThat(result).hasSize(1);
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
    @DisplayName("KIS 응답 오류 처리")
    class KisErrorHandling {

        @Test
        @DisplayName("rt_cd가 0이 아니면 조회 실패 예외로 감싼다")
        void nonZeroReturnCode_wrapsInFailure() {
            enqueueBody("""
                    {"rt_cd":"1","msg_cd":"EGW00123","msg1":"기간이 잘못되었습니다","output1":{},"output2":[]}
                    """);

            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "3month").block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("주식 차트 데이터 조회 실패");
        }

        @Test
        @DisplayName("응답이 JSON이 아니면 조회 실패 예외로 감싼다")
        void malformedBody_wrapsInFailure() {
            enqueueBody("<html>gateway error</html>");

            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "3month").block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("주식 차트 데이터 조회 실패");
        }

        @Test
        @DisplayName("성공 응답이지만 output2가 null이면 빈 리스트다 (예외 아님)")
        void nullOutput2_returnsEmptyList() {
            enqueueRawOutput2("null");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("실패한 조회는 캐시에 저장되지 않는다")
        void failedFetch_isNotCached() {
            enqueueBody("""
                    {"rt_cd":"1","msg_cd":"E","msg1":"오류","output1":{},"output2":[]}
                    """);

            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "3month").block())
                    .isInstanceOf(RuntimeException.class);

            assertThat(redisTemplate.opsForValue().get("stock:chart:" + STOCK_CODE + ":3month")).isNull();
        }
    }

    @Nested
    @DisplayName("output2 구조별 파싱")
    class OutputShapeParsing {

        @Test
        @DisplayName("배열 안에 Map이 아닌 항목이 섞이면 건너뛴다")
        void nonMapItemsInArray_areSkipped() {
            enqueueRawOutput2("[" + dailyRow(daysAgo(1), "") + ", \"쓰레기값\", 42]");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("output2가 output 키를 가진 객체면 그 안의 배열을 읽는다")
        void nestedOutputKey_withList() {
            enqueueRawOutput2("{\"output\":[" + dailyRow(daysAgo(2), "") + "," + dailyRow(daysAgo(1), "") + "]}");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("output 키 안이 단일 객체여도 한 건으로 읽는다")
        void nestedOutputKey_withSingleObject() {
            enqueueRawOutput2("{\"output\":" + dailyRow(daysAgo(1), "") + "}");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("output2가 output 키 없는 단일 객체면 그대로 한 건으로 읽는다")
        void bareObject_isReadAsSingleRow() {
            enqueueRawOutput2(dailyRow(daysAgo(1), ""));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("output2가 객체도 배열도 아니면 빈 리스트다")
        void scalarOutput_returnsEmpty() {
            enqueueRawOutput2("\"unexpected\"");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("날짜 형식이 어긋나면 조회 실패 예외로 감싼다")
        void invalidDateFormat_wrapsInFailure() {
            enqueueRawOutput2("[" + dailyRow("2026-09-01", "") + "]");

            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "3month").block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("주식 차트 데이터 조회 실패");
        }
    }

    @Nested
    @DisplayName("필드 변환")
    class FieldMapping {

        private StockChartResponse fetchSingleRow(String extraFields) {
            enqueueRawOutput2("[" + dailyRow(daysAgo(1), extraFields) + "]");
            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();
            assertThat(result).hasSize(1);
            return result.get(0);
        }

        @Test
        @DisplayName("전일대비율이 오면 그 값을 그대로 쓴다")
        void changeRate_presentIsUsedAsIs() {
            StockChartResponse row = fetchSingleRow("\"prdy_vrss\":\"1000\",\"prdy_ctrt\":\"1.45\"");

            assertThat(row.changeRate()).isEqualTo("1.45");
        }

        @Test
        @DisplayName("전일대비율이 없으면 전일대비와 종가로 계산한다")
        void changeRate_absentIsComputed() {
            // 종가 70000, 전일대비 1000 → 전일 종가 69000 기준 1000/69000 = 1.45%
            StockChartResponse row = fetchSingleRow("\"prdy_vrss\":\"1000\"");

            assertThat(row.changeRate()).isEqualTo("1.45");
        }

        @Test
        @DisplayName("전일대비가 0이면 계산하지 않고 0을 쓴다")
        void changeRate_zeroChangeFallsBackToZero() {
            StockChartResponse row = fetchSingleRow("\"prdy_vrss\":\"0\"");

            assertThat(row.changeRate()).isEqualTo("0");
        }

        @Test
        @DisplayName("숫자 필드가 비었거나 숫자가 아니면 0으로 떨어진다")
        void nonNumericFields_becomeZero() {
            enqueueRawOutput2("""
                    [{"stck_bsop_date":"%s","stck_clpr":"","stck_oprc":"안녕",
                      "stck_hgpr":null,"stck_lwpr":"68000","acml_vol":"not-a-number",
                      "acml_tr_pbmn":"70000000","prdy_vrss":"0"}]
                    """.formatted(daysAgo(1)));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "3month").block();

            StockChartResponse row = result.get(0);
            assertThat(row.closePrice()).isZero();
            assertThat(row.openPrice()).isZero();
            assertThat(row.highPrice()).isZero();
            assertThat(row.volume()).isZero();
            assertThat(row.lowPrice()).isEqualTo(68000);
        }

        @Test
        @DisplayName("숫자 앞뒤 공백은 잘라내고 변환한다")
        void paddedNumbers_areTrimmed() {
            StockChartResponse row = fetchSingleRow("\"prdy_vrss\":\"  1000  \"");

            assertThat(row.changeAmount()).isEqualTo(1000);
        }

        @Test
        @DisplayName("일/주/월봉은 시간 정보가 없다")
        void dailyChart_hasNoTime() {
            StockChartResponse row = fetchSingleRow("\"prdy_vrss\":\"0\"");

            assertThat(row.time()).isNull();
        }
    }

    @Nested
    @DisplayName("당일 분봉 (1day)")
    class IntradayMinuteChart {

        private static final String TODAY = "20260904";

        @Test
        @DisplayName("장 시작 전이면 KIS를 호출하지 않고 빈 리스트를 준다")
        void beforeMarketOpen_returnsEmptyWithoutCallingKis() {
            fixClockAt(2026, 9, 4, 8, 30);
            respondAlways(minuteBody(minuteRow(TODAY, "090000")));
            int before = KIS.getRequestCount();

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result).isEmpty();
            assertThat(KIS.getRequestCount() - before).isZero();
        }

        @Test
        @DisplayName("장 마감 후에는 09:00~15:30을 30분 단위로 14번 조회한다")
        void afterMarketClose_queriesFourteenBuckets() {
            respondAlways(minuteBody(minuteRow(TODAY, "090000")));
            int before = KIS.getRequestCount();

            stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(KIS.getRequestCount() - before).isEqualTo(14);
        }

        @Test
        @DisplayName("장중이면 현재 시각까지만 조회한다")
        void duringMarketHours_queriesUpToNow() {
            fixClockAt(2026, 9, 4, 10, 17);
            respondAlways(minuteBody(minuteRow(TODAY, "090000")));
            int before = KIS.getRequestCount();

            stockChartService.getStockChart(STOCK_CODE, "1day").block();

            // 09:00, 09:30, 10:00 + 종료시각(10:17) = 4회
            assertThat(KIS.getRequestCount() - before).isEqualTo(4);
        }

        @Test
        @DisplayName("여러 번 조회한 결과에서 같은 날짜+시간은 한 건으로 합친다")
        void duplicateTimestampsAcrossBuckets_areDeduplicated() {
            respondAlways(minuteBody(minuteRow(TODAY, "090000"), minuteRow(TODAY, "091000")));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result)
                    .as("14번 호출이 모두 같은 두 건을 돌려줘도 중복 제거된다")
                    .hasSize(2);
        }

        @Test
        @DisplayName("분봉은 날짜+시간 오름차순으로 정렬된다")
        void minuteRows_sortedByDateThenTime() {
            respondAlways(minuteBody(
                    minuteRow(TODAY, "103000"),
                    minuteRow(TODAY, "090000"),
                    minuteRow(TODAY, "094500")));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result).extracting(StockChartResponse::time)
                    .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 45), LocalTime.of(10, 30));
        }

        @Test
        @DisplayName("분봉은 현재가를 종가로 쓰고 전일대비는 0으로 채운다")
        void minuteRow_usesCurrentPriceAsCloseAndZeroChange() {
            respondAlways(minuteBody(minuteRow(TODAY, "090000")));

            StockChartResponse row = stockChartService.getStockChart(STOCK_CODE, "1day").block().get(0);

            assertThat(row.closePrice()).as("stck_prpr를 종가로 쓴다").isEqualTo(70500);
            assertThat(row.volume()).as("cntg_vol를 거래량으로 쓴다").isEqualTo(120L);
            assertThat(row.changeAmount()).isZero();
            assertThat(row.changeRate()).isEqualTo("0");
        }

        @Test
        @DisplayName("시간이 6자리가 아니면 time은 null이 된다")
        void malformedTime_becomesNull() {
            respondAlways(minuteBody(minuteRow(TODAY, "0900")));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).time()).isNull();
        }

        @Test
        @DisplayName("시간 자리에 숫자가 아닌 값이 오면 time은 null이 된다")
        void nonNumericTime_becomesNull() {
            respondAlways(minuteBody(minuteRow(TODAY, "ab0000")));

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result.get(0).time()).isNull();
        }

        @Test
        @DisplayName("시간이 null이면 time은 null이 된다")
        void nullTime_becomesNull() {
            respondAlways("""
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{},"output2":[
                      {"stck_bsop_date":"20260904","stck_cntg_hour":null,"stck_prpr":"70500",
                       "stck_oprc":"70000","stck_hgpr":"70800","stck_lwpr":"69900",
                       "cntg_vol":"120","acml_tr_pbmn":""}]}
                    """);

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).time()).isNull();
            assertThat(result.get(0).amount()).as("빈 문자열 거래대금은 0").isZero();
        }

        @Test
        @DisplayName("분봉 output2가 배열이 아니면 그 구간은 빈 값으로 넘어간다")
        void nonArrayMinuteOutput_yieldsNothing() {
            respondAlways("""
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{},"output2":"이상한값"}
                    """);

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("분봉 output2가 null이면 그 구간은 빈 값으로 넘어간다")
        void nullMinuteOutput_yieldsNothing() {
            respondAlways("""
                    {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":{},"output2":null}
                    """);

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1day").block();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("분봉 조회가 KIS 오류를 받으면 조회 실패로 감싼다")
        void minuteApiError_wrapsInFailure() {
            respondAlways("""
                    {"rt_cd":"1","msg_cd":"E","msg1":"분봉 오류","output1":{},"output2":[]}
                    """);

            assertThatThrownBy(() -> stockChartService.getStockChart(STOCK_CODE, "1day").block())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("주식 분봉 데이터 조회 실패");
        }
    }

    @Nested
    @DisplayName("주간 분봉 (1week)")
    class WeeklyMinuteChart {

        @Test
        @DisplayName("최근 5영업일 × 4개 시간창 = 20번 조회한다")
        void queriesFiveBusinessDaysTimesFourWindows() {
            respondPerRequestedDate("090000");
            int before = KIS.getRequestCount();

            stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(KIS.getRequestCount() - before).isEqualTo(20);
        }

        @Test
        @DisplayName("주말은 영업일에서 빠진다")
        void weekendsAreExcluded() {
            // 2026-09-06은 일요일. 최근 5영업일은 08-31(월)~09-04(금)이다.
            fixClockAt(2026, 9, 6, 16, 0);
            respondPerRequestedDate("090000");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(result).extracting(StockChartResponse::date)
                    .containsExactly(
                            LocalDate.of(2026, 8, 31),
                            LocalDate.of(2026, 9, 1),
                            LocalDate.of(2026, 9, 2),
                            LocalDate.of(2026, 9, 3),
                            LocalDate.of(2026, 9, 4));
        }

        @Test
        @DisplayName("요청한 날짜와 다른 날짜의 데이터는 버린다")
        void rowsFromOtherDates_areDropped() {
            KIS.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    // 요청 날짜와 무관하게 항상 같은 날짜를 돌려준다 → 해당 날짜 요청에서만 살아남는다.
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(minuteBody(minuteRow("20260902", "090000")));
                }
            });

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(result).extracting(StockChartResponse::date)
                    .as("09-02을 요청한 4번에서만 통과하고, 중복 제거로 한 건이 남는다")
                    .containsExactly(LocalDate.of(2026, 9, 2));
        }

        @Test
        @DisplayName("10분보다 촘촘한 데이터는 솎아낸다")
        void rowsCloserThanTenMinutes_areThinned() {
            respondPerRequestedDate("090000", "090300", "090700", "091500", "093000");

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(result).extracting(StockChartResponse::time)
                    .as("09:00 이후 10분 미만 간격은 버려진다")
                    .containsExactly(
                            LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30),
                            LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30),
                            LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30),
                            LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30),
                            LocalTime.of(9, 0), LocalTime.of(9, 15), LocalTime.of(9, 30));
        }

        @Test
        @DisplayName("응답이 비어 있으면 그 구간을 건너뛰고 예외를 내지 않는다")
        void blankResponse_isSkipped() {
            KIS.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setHeader("Content-Type", "application/json").setBody("");
                }
            });

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("HTML 응답은 인증 문제로 보고 그 구간을 건너뛴다")
        void htmlResponse_isSkipped() {
            KIS.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().setBody("<html>login required</html>");
                }
            });

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("rt_cd가 비어 있으면 그 구간을 건너뛴다")
        void blankReturnCode_isSkipped() {
            KIS.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"rt_cd\":\"\",\"msg1\":\"\",\"output1\":{},\"output2\":[]}");
                }
            });

            List<StockChartResponse> result = stockChartService.getStockChart(STOCK_CODE, "1week").block();

            assertThat(result).isEmpty();
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
