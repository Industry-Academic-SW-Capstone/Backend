package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.industry.repository.IndustryRepository;
import grit.stockIt.domain.stock.dto.IndustryStockRankingResponse;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockRankingService 배선 특성화 테스트 — 불변(immutable).
 *
 * 이 파일은 stock-ranking-charac-base 태그 이후 수정하지 않는다. 순수 로직 추출이
 * 관측 가능한 동작을 바꾸지 않았음을 증명하는 오라클이기 때문이다.
 *
 * public API만 호출한다. private 메서드를 직접 부르지 않으므로 추출 과정에서
 * 가시성이 바뀌어도 이 파일은 영향을 받지 않는다.
 *
 * Spring 컨텍스트를 띄우지 않고 생성자로 직접 조립한다. 대상 서비스가 생성자 주입만
 * 쓰기 때문에 가능하며, Testcontainers 기동 비용 없이 배선을 검증한다.
 */
@DisplayName("StockRankingService 배선 특성화 (불변)")
class StockRankingApiCharacterizationTest {

    private MockWebServer mockWebServer;
    private StockRankingService stockRankingService;
    private KisTokenManager kisTokenManager;
    private StockRepository stockRepository;
    private IndustryRepository industryRepository;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        kisTokenManager = mock(KisTokenManager.class);
        when(kisTokenManager.getAccessToken()).thenReturn("test-access-token");

        KisApiProperties kisApiProperties =
                new KisApiProperties("http://unused", "test-appkey", "test-appsecret");

        stockRepository = mock(StockRepository.class);
        industryRepository = mock(IndustryRepository.class);

        stockRankingService = new StockRankingService(
                webClient, kisTokenManager, kisApiProperties, stockRepository, industryRepository,
                new StockRankingParsingService(), new IndustryRankingCalculationService());
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

    private static Stock stock(String code, String marketType, String industryCode) {
        return Stock.builder()
                .code(code)
                .name("종목" + code)
                .marketType(marketType)
                .industryCode(industryCode)
                .build();
    }

    private static String amountRow(String code, String name, String amount, String volume,
                                    String price, String sign, String changeAmount, String rate) {
        return """
                {"mksc_shrn_iscd":"%s","hts_kor_isnm":"%s","data_rank":"1","stck_prpr":"%s",\
                "prdy_vrss_sign":"%s","prdy_vrss":"%s","prdy_ctrt":"%s","acml_vol":"%s",\
                "acml_tr_pbmn":"%s"}""".formatted(code, name, price, sign, changeAmount, rate, volume, amount);
    }

    @Test
    @DisplayName("거래대금 경로: KIS 호출 1회, TR ID FHPST01710000, 대문자 쿼리 파라미터")
    void amountPath_wiring() throws InterruptedException {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s]}""".formatted(amountRow("005930", "삼성전자", "1000", "10", "70000", "2", "500", "0.72")));
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001")));

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stockCode()).isEqualTo("005930");
        assertThat(result.get(0).marketType()).isEqualTo("KOSPI");

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("tr_id")).isEqualTo("FHPST01710000");
        assertThat(request.getHeader("authorization")).isEqualTo("Bearer test-access-token");
        assertThat(request.getHeader("appkey")).isEqualTo("test-appkey");
        assertThat(request.getHeader("appsecret")).isEqualTo("test-appsecret");
        assertThat(request.getHeader("custtype")).isEqualTo("P");

        String path = request.getPath();
        assertThat(path).contains("/uapi/domestic-stock/v1/quotations/volume-rank");
        assertThat(path).contains("FID_COND_MRKT_DIV_CODE=J");
        assertThat(path).contains("FID_BLNG_CLS_CODE=3");
        assertThat(path).doesNotContain("fid_cond_mrkt_div_code=");
    }

    @Test
    @DisplayName("등락 경로: TR ID FHPST01700000, 소문자 쿼리 파라미터, rise/fall 정렬 코드")
    void fluctuationPath_wiring() throws InterruptedException {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                {"stck_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","data_rank":"1",\
                "stck_prpr":"70000","prdy_vrss_sign":"2","prdy_vrss":"500","prdy_ctrt":"0.72",\
                "acml_vol":"10"}]}""");
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001")));

        List<StockRankingResponse> result =
                stockRankingService.getFluctuationTopStocksFiltered(30, true).block();

        assertThat(result).hasSize(1);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("tr_id")).isEqualTo("FHPST01700000");

        String path = request.getPath();
        assertThat(path).contains("/uapi/domestic-stock/v1/ranking/fluctuation");
        assertThat(path).contains("fid_cond_mrkt_div_code=J");
        assertThat(path).contains("fid_rank_sort_cls_code=0");
        assertThat(path).doesNotContain("FID_COND_MRKT_DIV_CODE=");
    }

    @Test
    @DisplayName("등락 하락 정렬은 fid_rank_sort_cls_code=1")
    void fluctuationPath_fallUsesSortCode1() throws InterruptedException {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[]}""");
        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of());

        stockRankingService.getFluctuationTopStocksFiltered(30, false).block();

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).contains("fid_rank_sort_cls_code=1");
    }

    @Test
    @DisplayName("trim 비대칭: 같은 응답에서 stck_prpr ' 12345'는 12345, acml_tr_pbmn ' 12345'는 0")
    void trimAsymmetry_isPreserved() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","data_rank":"1",\
                "stck_prpr":" 12345","prdy_vrss_sign":"2","prdy_vrss":" 500","prdy_ctrt":"0.72",\
                "acml_vol":" 999","acml_tr_pbmn":" 12345"}]}""");
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001")));

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).hasSize(1);
        StockRankingResponse row = result.get(0);
        assertThat(row.currentPrice()).isEqualTo(12345);
        assertThat(row.changeAmount()).isEqualTo(500);
        assertThat(row.amount()).isEqualTo(0L);
        assertThat(row.volume()).isEqualTo(0L);
    }

    @Test
    @DisplayName("등락 응답에는 acml_tr_pbmn이 없어 amount가 전 원소 0")
    void fluctuationAmount_isAlwaysZero() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                {"stck_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","data_rank":"1",\
                "stck_prpr":"70000","prdy_vrss_sign":"2","prdy_vrss":"500","prdy_ctrt":"0.72",\
                "acml_vol":"111"},
                {"stck_shrn_iscd":"000660","hts_kor_isnm":"SK하이닉스","data_rank":"2",\
                "stck_prpr":"180000","prdy_vrss_sign":"5","prdy_vrss":"-1000","prdy_ctrt":"-0.55",\
                "acml_vol":"222"}]}""");
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001"), stock("000660", "KOSPI", "0001")));

        List<StockRankingResponse> result =
                stockRankingService.getFluctuationTopStocksFiltered(30, true).block();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(StockRankingResponse::amount).containsExactly(0L, 0L);
        assertThat(result).extracting(StockRankingResponse::volume).containsExactly(111L, 222L);
    }

    @Test
    @DisplayName("output이 List도 Map도 아니면 빈 리스트를 반환하고 예외를 던지지 않는다")
    void malformedOutput_yieldsEmptyList() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":"unexpected-string"}""");
        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of());

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("output이 Map이고 data 키가 List면 정상 파싱된다")
    void mapOutputWithDataKey_isParsed() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":{"data":[
                %s]}}""".formatted(amountRow("005930", "삼성전자", "5000", "50", "70000", "2", "500", "0.72")));
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001")));

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amount()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("prdy_vrss_sign이 없으면 changeSign은 STEADY")
    void missingChangeSign_mapsToSteady() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","data_rank":"1",\
                "stck_prpr":"70000","prdy_vrss":"500","prdy_ctrt":"0.72","acml_vol":"10",\
                "acml_tr_pbmn":"1000"}]}""");
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001")));

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).changeSign())
                .isEqualTo(StockRankingResponse.PriceChangeSign.STEADY);
    }

    @Test
    @DisplayName("알려진 등락 부호 코드는 각각의 enum으로 매핑된다")
    void knownChangeSignCodes_mapToEnums() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s,
                %s]}""".formatted(
                amountRow("005930", "삼성전자", "2000", "20", "70000", "1", "500", "0.72"),
                amountRow("000660", "SK하이닉스", "1000", "10", "180000", "5", "-1000", "-0.55")));
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001"), stock("000660", "KOSPI", "0001")));

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).extracting(StockRankingResponse::changeSign)
                .containsExactly(
                        StockRankingResponse.PriceChangeSign.UPPER_LIMIT,
                        StockRankingResponse.PriceChangeSign.FALL);
    }

    @Test
    @DisplayName("DB에 없는 종목은 필터링되고 marketType은 DB 값으로 교체된다")
    void dbFilter_replacesMarketType() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s,
                %s]}""".formatted(
                amountRow("005930", "삼성전자", "2000", "20", "70000", "2", "500", "0.72"),
                amountRow("999999", "미등록ETF", "9000", "90", "10000", "2", "100", "1.01")));
        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSDAQ", "0001")));

        List<StockRankingResponse> result = stockRankingService.getAmountTopStocksFiltered(30).block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stockCode()).isEqualTo("005930");
        assertThat(result.get(0).marketType()).isEqualTo("KOSDAQ");
    }

    @Test
    @DisplayName("업종별: 합계 내림차순 정렬, 업종당 상한 5")
    void industries_sortedByAmountSumAndCappedAtFive() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s,%s,%s,%s,%s,%s,%s]}""".formatted(
                amountRow("A01", "A종목1", "100", "1", "1000", "2", "10", "1.0"),
                amountRow("A02", "A종목2", "90", "1", "1000", "2", "10", "1.0"),
                amountRow("A03", "A종목3", "80", "1", "1000", "2", "10", "1.0"),
                amountRow("A04", "A종목4", "70", "1", "1000", "2", "10", "1.0"),
                amountRow("A05", "A종목5", "60", "1", "1000", "2", "10", "1.0"),
                amountRow("A06", "A종목6", "50", "1", "1000", "2", "10", "1.0"),
                amountRow("B01", "B종목1", "10000", "1", "1000", "2", "10", "1.0")));

        List<Stock> stocks = List.of(
                stock("A01", "KOSPI", "0001"), stock("A02", "KOSPI", "0001"),
                stock("A03", "KOSPI", "0001"), stock("A04", "KOSPI", "0001"),
                stock("A05", "KOSPI", "0001"), stock("A06", "KOSPI", "0001"),
                stock("B01", "KOSPI", "0002"));
        when(stockRepository.findByCodeIn(anyList())).thenReturn(stocks);
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("전기전자").build(),
                Industry.builder().code("0002").name("금융").build()));

        List<IndustryStockRankingResponse> result =
                stockRankingService.getPopularStocksByIndustry(30).block();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).industryCode()).isEqualTo("0002");
        assertThat(result.get(1).industryCode()).isEqualTo("0001");
        assertThat(result.get(1).stocks()).hasSize(5);
        assertThat(result.get(1).stocks())
                .extracting(StockRankingResponse::stockCode)
                .containsExactly("A01", "A02", "A03", "A04", "A05");
    }

    @Test
    @DisplayName("업종별: 합계 동점 업종의 현재 산출 순서를 동결한다")
    void industries_tieOrderIsFrozen() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s,%s,%s]}""".formatted(
                amountRow("T01", "동점1", "500", "1", "1000", "2", "10", "1.0"),
                amountRow("T02", "동점2", "500", "1", "1000", "2", "10", "1.0"),
                amountRow("T03", "동점3", "500", "1", "1000", "2", "10", "1.0")));

        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of(
                stock("T01", "KOSPI", "0001"),
                stock("T02", "KOSPI", "0002"),
                stock("T03", "KOSPI", "0003")));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("업종1").build(),
                Industry.builder().code("0002").name("업종2").build(),
                Industry.builder().code("0003").name("업종3").build()));

        List<IndustryStockRankingResponse> result =
                stockRankingService.getPopularStocksByIndustry(30).block();

        assertThat(result).hasSize(3);
        // 합계가 전부 500으로 동점이므로 Stream.sorted(안정 정렬)는 순서를 바꾸지 않는다.
        // 따라서 출력 순서는 groupingBy가 반환한 HashMap의 버킷 배치가 결정한다.
        // 삽입 순서(0001, 0002, 0003)가 아닌 아래 순서가 현재 동작이며, 이 값을 동결한다.
        assertThat(result)
                .extracting(IndustryStockRankingResponse::industryCode)
                .containsExactly("0002", "0003", "0001");
    }

    @Test
    @DisplayName("업종별: Industry 행이 없으면 industryName은 null")
    void industries_missingIndustryRowYieldsNullName() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s]}""".formatted(amountRow("X01", "고아종목", "100", "1", "1000", "2", "10", "1.0")));

        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("X01", "KOSPI", "9999")));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of());

        List<IndustryStockRankingResponse> result =
                stockRankingService.getPopularStocksByIndustry(30).block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).industryCode()).isEqualTo("9999");
        assertThat(result.get(0).industryName()).isNull();
    }

    @Test
    @DisplayName("업종별: industryCode가 null인 종목은 그룹에서 제외된다")
    void industries_nullIndustryCodeIsExcluded() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s,%s]}""".formatted(
                amountRow("N01", "업종있음", "100", "1", "1000", "2", "10", "1.0"),
                amountRow("N02", "업종없음", "900", "1", "1000", "2", "10", "1.0")));

        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of(
                stock("N01", "KOSPI", "0001"),
                stock("N02", "KOSPI", null)));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("업종1").build()));

        List<IndustryStockRankingResponse> result =
                stockRankingService.getPopularStocksByIndustry(30).block();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).industryCode()).isEqualTo("0001");
        assertThat(result.get(0).stocks())
                .extracting(StockRankingResponse::stockCode)
                .containsExactly("N01");
    }

    @Test
    @DisplayName("업종별: KIS 호출 1회, findByCodeIn 2회, findAllById 1회")
    void industries_dbCallProfile() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                %s]}""".formatted(amountRow("005930", "삼성전자", "100", "1", "1000", "2", "10", "1.0")));

        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "KOSPI", "0001")));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("전기전자").build()));

        stockRankingService.getPopularStocksByIndustry(30).block();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
        verify(stockRepository, times(2)).findByCodeIn(anyList());
        verify(industryRepository, times(1)).findAllById(anyList());
    }

    @Test
    @DisplayName("토큰은 구독 이전 assembly-time에 동기 획득된다")
    void token_isFetchedEagerlyAtAssemblyTime() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[]}""");

        verify(kisTokenManager, never()).getAccessToken();

        stockRankingService.getAmountTopStocks(30);

        verify(kisTokenManager, times(1)).getAccessToken();
        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("파싱 실패는 거래대금 조회 실패로 재포장된다")
    void amountPath_parseFailureIsRewrapped() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rt_cd\":\"0\",\"output\":[{\"mksc_shrn_iscd\":12345}]}"));

        Throwable thrown = catchThrowableOfMono(() -> stockRankingService.getAmountTopStocks(30).block());

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown.getMessage()).isEqualTo("거래대금 상위 종목 조회 실패");
        assertThat(thrown.getCause()).isNotNull();
        assertThat(thrown.getCause().getMessage()).isEqualTo("응답 파싱 실패");
    }

    @Test
    @DisplayName("업종별 조회는 거래대금 실패를 이중으로 포장한다")
    void industries_doubleWrapsAmountFailure() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rt_cd\":\"0\",\"output\":[{\"mksc_shrn_iscd\":12345}]}"));

        Throwable thrown =
                catchThrowableOfMono(() -> stockRankingService.getPopularStocksByIndustry(30).block());

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown.getMessage()).isEqualTo("업종별 인기 종목 조회 실패");
        assertThat(thrown.getCause().getMessage()).isEqualTo("거래대금 상위 종목 조회 실패");
        assertThat(thrown.getCause().getCause().getMessage()).isEqualTo("응답 파싱 실패");
    }

    @Test
    @DisplayName("등락 파싱 실패 메시지는 등락 전용 문구를 유지한다")
    void fluctuationPath_parseFailureMessage() {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"rt_cd\":\"0\",\"output\":[{\"stck_shrn_iscd\":12345}]}"));

        Throwable thrown =
                catchThrowableOfMono(() -> stockRankingService.getFluctuationTopStocks(true).block());

        assertThat(thrown.getMessage()).isEqualTo("급등 순위 조회 실패");
        assertThat(thrown.getCause().getMessage()).isEqualTo("응답 파싱 실패(등락)");
    }

    private static Throwable catchThrowableOfMono(Runnable blockingCall) {
        try {
            blockingCall.run();
            return null;
        } catch (Throwable t) {
            return unwrapReactiveException(t);
        }
    }

    /**
     * block()은 원인 예외를 ReactiveException으로 감싸 던진다. 특성화 대상은 서비스가 만든
     * 예외 체인이므로 그 래퍼 한 겹만 벗겨낸다.
     */
    private static Throwable unwrapReactiveException(Throwable thrown) {
        if (thrown.getClass().getName().contains("ReactiveException") && thrown.getCause() != null) {
            return thrown.getCause();
        }
        return thrown;
    }
}
