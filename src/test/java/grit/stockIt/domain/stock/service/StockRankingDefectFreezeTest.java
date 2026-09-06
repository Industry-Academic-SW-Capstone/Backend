package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.controller.StockRankingController;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import grit.stockIt.global.exception.GlobalExceptionHandler;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StockRankingService 선존 결함 동결 테스트 — 불변(immutable).
 *
 * 여기 고정된 동작은 전부 결함이다. 고치지 않고 동결하는 이유는 이번 사이클의 목적이
 * 순수 로직 추출이기 때문이다. 결함을 고치는 후속 커밋이 이 파일을 갱신하며,
 * 그때 발생하는 diff가 곧 사용자 영향 명세가 된다.
 *
 * MockMvc standaloneSetup + GlobalExceptionHandler로 HTTP 응답 계층까지 관측한다.
 * Mono 반환 핸들러는 asyncDispatch가 필요하나, 컨트롤러가 동기 throw 대신
 * Mono.error를 쓰는 경로는 예외가 async 처리 중 표면화되므로 결과를 직접 확인한다.
 */
@DisplayName("StockRankingService 선존 결함 동결 (불변)")
class StockRankingDefectFreezeTest {

    private MockWebServer mockWebServer;
    private MockMvc mockMvc;
    private StockRepository stockRepository;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        KisTokenManager kisTokenManager = mock(KisTokenManager.class);
        when(kisTokenManager.getAccessToken()).thenReturn("test-access-token");

        stockRepository = mock(StockRepository.class);

        StockRankingService service = new StockRankingService(
                webClient,
                kisTokenManager,
                new KisApiProperties("http://unused", "test-appkey", "test-appsecret"),
                stockRepository,
                mock(grit.stockIt.domain.industry.repository.IndustryRepository.class));

        mockMvc = MockMvcBuilders
                .standaloneSetup(new StockRankingController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(productionObjectMapper()))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    private void enqueueJson(String body) {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    /**
     * standaloneSetup은 Boot의 Jackson 자동 설정을 적용하지 않는다. 이 테스트가 동결하는 것이
     * 바로 JSON 와이어 포맷이므로 application.yml의 SNAKE_CASE + non_null을 그대로 재현한다.
     */
    private static ObjectMapper productionObjectMapper() {
        return new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private static Stock stock(String code) {
        return Stock.builder()
                .code(code)
                .name("종목" + code)
                .marketType("KOSPI")
                .industryCode("0001")
                .build();
    }

    /**
     * 결함 #5 — 잘못된 type 파라미터가 HTTP 400이 아니라 500을 낸다.
     * GlobalExceptionHandler에 IllegalArgumentException 핸들러가 없어 handleAll로 귀결된다.
     * 클라이언트 입력 오류가 서버 오류로 보고되는 상태를 동결한다.
     */
    @Test
    @DisplayName("결함 #5: 잘못된 type은 400이 아니라 500을 반환한다")
    void defect5_invalidTypeReturns500NotBadRequest() throws Exception {
        MvcResult asyncResult = mockMvc.perform(get("/api/stocks/fluctuations").param("type", "bogus"))
                .andReturn();

        // Mono.error가 async 디스패치 중 표면화되므로 예외를 직접 확인한다.
        Exception resolved = asyncResult.getResolvedException();
        if (resolved == null) {
            Object asyncException = asyncResult.getAsyncResult();
            assertThat(asyncException).isInstanceOf(IllegalArgumentException.class);
            assertThat(((Throwable) asyncException).getMessage())
                    .isEqualTo("type 파라미터는 rise 또는 fall 이어야 합니다.");
        } else {
            assertThat(resolved).isInstanceOf(IllegalArgumentException.class);
        }

        // 핵심 동결점: 이 예외를 400으로 바꿔주는 핸들러가 존재하지 않는다.
        boolean hasIllegalArgumentHandler = java.util.Arrays
                .stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .flatMap(m -> java.util.Arrays.stream(
                        m.getAnnotationsByType(org.springframework.web.bind.annotation.ExceptionHandler.class)))
                .flatMap(a -> java.util.Arrays.stream(a.value()))
                .anyMatch(c -> c == IllegalArgumentException.class);
        assertThat(hasIllegalArgumentHandler).isFalse();
    }

    /**
     * 결함 #5 보강 — handleAll이 실제로 500과 고정 메시지를 낸다.
     */
    @Test
    @DisplayName("결함 #5: handleAll은 500과 고정 메시지를 낸다")
    void defect5_handleAllShape() throws Exception {
        MockMvc failing = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        failing.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("서버 처리 중 오류가 발생했습니다."));
    }

    @org.springframework.web.bind.annotation.RestController
    static class ThrowingController {
        @org.springframework.web.bind.annotation.GetMapping("/boom")
        public String boom() {
            throw new IllegalArgumentException("type 파라미터는 rise 또는 fall 이어야 합니다.");
        }
    }

    /**
     * 결함 #7 — /fluctuations 응답의 amount가 전부 0이다.
     * 등락 API 응답에 acml_tr_pbmn이 없어 기본값 "0"이 들어간다.
     * 프론트가 거래대금을 그리면 전 행이 0원으로 표시된다.
     */
    @Test
    @DisplayName("결함 #7: /fluctuations의 amount는 항상 0으로 나간다")
    void defect7_fluctuationAmountIsAlwaysZero() throws Exception {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                {"stck_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","data_rank":"1",\
                "stck_prpr":"70000","prdy_vrss_sign":"2","prdy_vrss":"500","prdy_ctrt":"0.72",\
                "acml_vol":"12345"}]}""");
        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of(stock("005930")));

        MvcResult mvcResult = mockMvc.perform(get("/api/stocks/fluctuations").param("type", "rise"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stock_code").value("005930"))
                .andExpect(jsonPath("$[0].amount").value(0))
                .andExpect(jsonPath("$[0].volume").value(12345));
    }

    /**
     * 결함 #9 — 비정형 output이 예외가 아니라 빈 배열 + HTTP 200으로 나간다.
     * rt_cd를 로그로만 찍고 검증하지 않으므로 KIS 업무 오류와 "장이 빈 상태"를
     * 클라이언트가 구분할 수 없다.
     */
    @Test
    @DisplayName("결함 #9: 비정형 output은 빈 배열 + HTTP 200, rt_cd 미검증")
    void defect9_malformedOutputYieldsEmpty200() throws Exception {
        enqueueJson("""
                {"rt_cd":"7","msg_cd":"EGW00123","msg1":"모의투자 조회가 불가합니다",\
                "output":"unexpected-string"}""");
        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of());

        MvcResult mvcResult = mockMvc.perform(get("/api/stocks/amount"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * 결함 #10 — 종목코드가 없는 행이 경고 없이 사라진다.
     */
    @Test
    @DisplayName("결함 #10: 종목코드 없는 행은 조용히 드롭된다")
    void defect10_nullStockCodeIsSilentlyDropped() throws Exception {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","data_rank":"1",\
                "stck_prpr":"70000","prdy_vrss_sign":"2","prdy_vrss":"500","prdy_ctrt":"0.72",\
                "acml_vol":"10","acml_tr_pbmn":"1000"},
                {"hts_kor_isnm":"코드없음","data_rank":"2","stck_prpr":"1000",\
                "prdy_vrss_sign":"2","prdy_vrss":"10","prdy_ctrt":"1.0","acml_vol":"20",\
                "acml_tr_pbmn":"99999"}]}""");
        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of(stock("005930")));

        MvcResult mvcResult = mockMvc.perform(get("/api/stocks/amount"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 거래대금이 더 큰 행(99999)이 코드 부재만으로 사라지고, 오류도 경고도 응답에 없다.
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].stock_code").value("005930"));
    }

    /**
     * 결함 #3 — 사전 절단. DB 존재 필터보다 .limit이 먼저 걸려서
     * Swagger가 약속한 "상위 30개"보다 적게 나간다.
     * ETF/ETN은 stock 테이블에 없으므로 실제로 재현된다.
     */
    @Test
    @DisplayName("결함 #3: 사전 절단으로 30개 요청에 30개 미만이 나간다")
    void defect3_preFilterTruncationYieldsFewerThanRequested() throws Exception {
        String rows = IntStream.rangeClosed(1, 35)
                .mapToObj(i -> """
                        {"mksc_shrn_iscd":"%06d","hts_kor_isnm":"종목%d","data_rank":"%d",\
                        "stck_prpr":"1000","prdy_vrss_sign":"2","prdy_vrss":"10",\
                        "prdy_ctrt":"1.0","acml_vol":"10","acml_tr_pbmn":"%d"}"""
                        .formatted(i, i, i, 100000 - i))
                .collect(Collectors.joining(","));
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[%s]}""".formatted(rows));

        // 상위 30개 중 5개가 DB에 없는 ETF라고 가정한다.
        List<Stock> present = IntStream.rangeClosed(6, 35)
                .mapToObj(i -> stock("%06d".formatted(i)))
                .collect(Collectors.toList());
        when(stockRepository.findByCodeIn(anyList())).thenReturn(present);

        MvcResult mvcResult = mockMvc.perform(get("/api/stocks/amount"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 사전 .limit(30)이 원시 35행을 30행으로 자른 뒤 DB 필터가 5행을 더 지운다.
        // 31~35번 종목은 DB에 있는데도 사전 절단 때문에 후보에 들지 못한다.
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(25));
    }

}
