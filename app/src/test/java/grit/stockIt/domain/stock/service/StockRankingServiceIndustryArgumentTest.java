package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.industry.repository.IndustryRepository;
import grit.stockIt.domain.stock.dto.IndustryStockRankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StockRankingService가 협력자에게 "무엇을" 넘기는지 검증한다.
 *
 * 불변 특성화 파일은 findByCodeIn/findAllById를 anyList()로 스텁하므로 호출 횟수만
 * 고정하고 인자 내용은 보지 않는다. 그래서 종목코드 목록이 통째로 비는 결함을
 * 잡아내지 못한다(뮤테이션 4 생존). 이 파일이 그 구멍을 메운다.
 *
 * 불변 파일이 아니므로 자유롭게 수정할 수 있다.
 */
@DisplayName("StockRankingService 협력자 인자 검증")
class StockRankingServiceIndustryArgumentTest {

    private MockWebServer mockWebServer;
    private StockRankingService stockRankingService;
    private StockRepository stockRepository;
    private IndustryRepository industryRepository;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        WebClient webClient = WebClient.builder()
                .baseUrl(mockWebServer.url("/").toString())
                .build();

        KisTokenManager kisTokenManager = mock(KisTokenManager.class);
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

    private static Stock stock(String code, String industryCode) {
        return Stock.builder()
                .code(code)
                .name("종목" + code)
                .marketType("KOSPI")
                .industryCode(industryCode)
                .build();
    }

    @Test
    @DisplayName("업종 조회용 종목코드 목록에 실제 종목코드가 담겨 나간다")
    void passesActualStockCodesToRepository() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                  {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","acml_tr_pbmn":"900"},
                  {"mksc_shrn_iscd":"000660","hts_kor_isnm":"SK하이닉스","acml_tr_pbmn":"500"}
                ]}""");

        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of(
                stock("005930", "0001"),
                stock("000660", "0001")));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("전기전자").build()));

        List<IndustryStockRankingResponse> result =
                stockRankingService.getPopularStocksByIndustry(30).block();

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockRepository, times(2)).findByCodeIn(captor.capture());

        // 두 번째 호출이 업종 조회 경로다. 종목코드 필터가 뒤집히면 여기가 빈 리스트가 된다.
        // (뮤테이션 4 검출 지점 — 불변 특성화의 anyList() 스텁으로는 잡히지 않는다)
        List<String> industryPathCodes = captor.getAllValues().get(1);
        assertThat(industryPathCodes).containsExactly("005930", "000660");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stocks()).hasSize(2);
    }

    @Test
    @DisplayName("업종코드 목록은 정렬된 순서 그대로 findAllById에 전달된다")
    void passesSortedIndustryCodesUnchanged() {
        // 업종 합계: 0002 = 900, 0001 = 500+100 = 600 -> 0002가 앞선다
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                  {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","acml_tr_pbmn":"500"},
                  {"mksc_shrn_iscd":"000660","hts_kor_isnm":"SK하이닉스","acml_tr_pbmn":"900"},
                  {"mksc_shrn_iscd":"035720","hts_kor_isnm":"카카오","acml_tr_pbmn":"100"}
                ]}""");

        when(stockRepository.findByCodeIn(anyList())).thenReturn(List.of(
                stock("005930", "0001"),
                stock("000660", "0002"),
                stock("035720", "0001")));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("전기전자").build(),
                Industry.builder().code("0002").name("반도체").build()));

        stockRankingService.getPopularStocksByIndustry(30).block();

        ArgumentCaptor<Iterable<String>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(industryRepository, times(1)).findAllById(captor.capture());

        // groupAndSort가 돌려준 순서 그대로여야 한다. 재정렬/중복제거가 끼면 여기서 깨진다.
        assertThat(captor.getValue()).containsExactly("0002", "0001");
    }

    @Test
    @DisplayName("종목코드가 null인 행은 업종 조회 인자에서 빠진다")
    void dropsNullStockCodesFromRepositoryArgument() {
        enqueueJson("""
                {"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":[
                  {"mksc_shrn_iscd":"005930","hts_kor_isnm":"삼성전자","acml_tr_pbmn":"900"},
                  {"hts_kor_isnm":"코드없음","acml_tr_pbmn":"800"}
                ]}""");

        when(stockRepository.findByCodeIn(anyList()))
                .thenReturn(List.of(stock("005930", "0001")));
        when(industryRepository.findAllById(anyList())).thenReturn(List.of(
                Industry.builder().code("0001").name("전기전자").build()));

        stockRankingService.getPopularStocksByIndustry(30).block();

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(stockRepository, times(2)).findByCodeIn(captor.capture());

        // 첫 호출(거래대금 필터 경로)은 null을 걸러내지 않아 null이 그대로 들어간다.
        // 두 번째 호출(업종 경로)만 null을 걸러낸다. 이 비대칭이 현재 동작이다(결함 #10).
        assertThat(captor.getAllValues().get(0)).containsExactly("005930", null);
        assertThat(captor.getAllValues().get(1)).containsExactly("005930");
    }
}
