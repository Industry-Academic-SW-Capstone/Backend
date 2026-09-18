package grit.stockIt.domain.stock.service;

import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.support.IntegrationTestSupport;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.MockWebServer;
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
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("시장가 홀딩 산정을 위한 상한가 조회·캐시 (통합 테스트)")
class UpperLimitPriceCacheTest extends IntegrationTestSupport {

    private static final MockWebServer KIS = new MockWebServer();

    private static final String PRICE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String STOCK_CODE = "005930";
    private static final String UPPER_LIMIT_KEY = "sim:price:upperlimit:005930";
    private static final String LAST_PRICE_KEY = "sim:price:last:005930";

    private static final String UPPER_LIMIT = "91000";
    private static final String CURRENT_PRICE = "70000";

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    // 상한가가 비어 오는 종목(거래정지 등)을 흉내내기 위한 토글.
    private static volatile String upperLimitPayload = UPPER_LIMIT;

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
                if (url == null || !PRICE_PATH.equals(url.encodedPath())) {
                    return new MockResponse().setResponseCode(404);
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(priceEnvelope(upperLimitPayload));
            }
        });
    }

    @DynamicPropertySource
    static void kisApiUrl(DynamicPropertyRegistry registry) {
        registry.add("kis.api.url", UpperLimitPriceCacheTest::mockBaseUrl);
    }

    @Autowired
    private StockDetailService stockDetailService;

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
        upperLimitPayload = UPPER_LIMIT;
        redisTemplate.delete(UPPER_LIMIT_KEY);
        redisTemplate.delete(LAST_PRICE_KEY);
    }

    @Test
    @DisplayName("캐시가 비면 KIS 에서 상한가(stck_mxpr)를 읽어 반환한다")
    void cacheMiss_readsUpperLimitFromKis() {
        BigDecimal upperLimit = stockDetailService.getUpperLimitPrice(STOCK_CODE).block(TIMEOUT);

        assertThat(upperLimit).isEqualByComparingTo(UPPER_LIMIT);
    }

    @Test
    @DisplayName("조회한 상한가는 전용 키에 저장되며 최근가 키를 건드리지 않는다")
    void cacheMiss_storesUnderOwnKey() {
        stockDetailService.getUpperLimitPrice(STOCK_CODE).block(TIMEOUT);

        assertThat(redisTemplate.opsForValue().get(UPPER_LIMIT_KEY)).isEqualTo(UPPER_LIMIT);
        assertThat(redisTemplate.hasKey(LAST_PRICE_KEY)).isFalse();
    }

    @Test
    @DisplayName("상한가 TTL 은 다음 자정까지다 — 매매일이 바뀌면 값이 달라지기 때문")
    void cacheTtl_lastsUntilNextMidnight() {
        stockDetailService.getUpperLimitPrice(STOCK_CODE).block(TIMEOUT);

        ZonedDateTime now = ZonedDateTime.now(KST);
        long expected = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(KST)).getSeconds();

        // Redis 는 밀리초 잔여를 올림해 돌려주므로 기대값보다 1초 클 수 있다.
        assertThat(redisTemplate.getExpire(UPPER_LIMIT_KEY)).isBetween(expected - 5, expected + 2);
    }

    @Test
    @DisplayName("캐시가 있으면 KIS 를 호출하지 않는다")
    void cacheHit_doesNotCallKis() {
        stockDetailService.getUpperLimitPrice(STOCK_CODE).block(TIMEOUT);
        int callsAfterFirst = KIS.getRequestCount();

        BigDecimal cached = stockDetailService.getUpperLimitPrice(STOCK_CODE).block(TIMEOUT);

        assertThat(cached).isEqualByComparingTo(UPPER_LIMIT);
        assertThat(KIS.getRequestCount()).isEqualTo(callsAfterFirst);
    }

    @Test
    @DisplayName("상한가가 비어 오면 예외 — 추정값으로 대체하지 않는다")
    void missingUpperLimit_fails() {
        upperLimitPayload = "";

        assertThatThrownBy(() -> stockDetailService.getUpperLimitPrice(STOCK_CODE).block(TIMEOUT))
                .hasStackTraceContaining("상한가를 가져올 수 없습니다");

        assertThat(redisTemplate.hasKey(UPPER_LIMIT_KEY)).isFalse();
    }

    private static String priceEnvelope(String upperLimit) {
        return """
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상처리 되었습니다.",
                 "output":{"stck_prpr":"%s","stck_mxpr":"%s","stck_llam":"49000","stck_sdpr":"70000"}}
                """.formatted(CURRENT_PRICE, upperLimit);
    }

    private static String mockBaseUrl() {
        return "http://localhost:" + KIS.getPort();
    }
}
