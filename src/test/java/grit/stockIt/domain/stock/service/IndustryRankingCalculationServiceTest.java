package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.stock.dto.IndustryStockRankingResponse;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IndustryRankingCalculationService 순수 단위 테스트.
 *
 * 협력자가 없으므로 목도 스프링 컨텍스트도 없이 new로 만들어 호출한다.
 * 여기서 고정하는 것은 "업종 랭킹 규칙"이다: 업종 정렬 기준, 업종당 상한,
 * 종목 정렬 기준, 그리고 호출자가 지켜야 하는 계약(재정렬 금지).
 */
class IndustryRankingCalculationServiceTest {

    private final IndustryRankingCalculationService service = new IndustryRankingCalculationService();

    // ---------- 픽스처 헬퍼 ----------

    private static StockRankingResponse stock(String code, long amount) {
        return new StockRankingResponse(
                code, code + "종목", 0L, amount, "UNKNOWN",
                0, 0, "0.0", StockRankingResponse.PriceChangeSign.STEADY);
    }

    private static StockRankingResponse stock(String code, long amount, long volume) {
        return new StockRankingResponse(
                code, code + "종목", volume, amount, "UNKNOWN",
                0, 0, "0.0", StockRankingResponse.PriceChangeSign.STEADY);
    }

    /** industryCode만 의미 있는 Stock 스텁. 리플렉션 대신 빌더로 만든다. */
    private static Stock stockEntity(String code, String industryCode) {
        return Stock.builder()
                .code(code)
                .name(code + "종목")
                .marketType("KOSPI")
                .industryCode(industryCode)
                .build();
    }

    private static Map<String, Stock> stockMap(Map<String, String> codeToIndustry) {
        Map<String, Stock> map = new HashMap<>();
        codeToIndustry.forEach((code, industry) -> map.put(code, stockEntity(code, industry)));
        return map;
    }

    private static Industry industry(String code, String name) {
        return Industry.builder().code(code).name(name).build();
    }

    @Nested
    @DisplayName("groupAndSort - 업종 그룹핑과 정렬")
    class GroupAndSort {

        @Test
        @DisplayName("업종별 거래대금 합계 내림차순으로 업종을 정렬한다")
        void sortsIndustriesByAmountSumDescending() {
            // 합계: A=100, B=500, C=300 -> B, C, A 순
            List<StockRankingResponse> allStocks = List.of(
                    stock("001", 100),
                    stock("002", 500),
                    stock("003", 300));
            Map<String, Stock> stockMap = stockMap(Map.of(
                    "001", "A", "002", "B", "003", "C"));

            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(allStocks, stockMap);

            // 뮤테이션 3(Long.compare 인자 반전) 검출 지점
            assertThat(result.sortedIndustryCodes()).containsExactly("B", "C", "A");
        }

        @Test
        @DisplayName("한 업종에 여러 종목이 있으면 거래대금을 합산해 정렬한다")
        void sumsAmountsWithinIndustry() {
            // A = 60+70 = 130, B = 100 -> A가 앞선다 (개별 최대값은 B가 크다)
            List<StockRankingResponse> allStocks = List.of(
                    stock("001", 60),
                    stock("002", 70),
                    stock("003", 100));
            Map<String, Stock> stockMap = stockMap(Map.of(
                    "001", "A", "002", "A", "003", "B"));

            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(allStocks, stockMap);

            // 개별 최대값이 아니라 합계로 정렬한다는 증거
            assertThat(result.sortedIndustryCodes()).containsExactly("A", "B");
            assertThat(result.stocksByIndustry().get("A")).hasSize(2);
        }

        @Test
        @DisplayName("stockMap에 없는 종목은 제외한다")
        void excludesStocksMissingFromStockMap() {
            List<StockRankingResponse> allStocks = List.of(
                    stock("001", 100),
                    stock("999", 500)); // stockMap에 없음
            Map<String, Stock> stockMap = stockMap(Map.of("001", "A"));

            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(allStocks, stockMap);

            assertThat(result.sortedIndustryCodes()).containsExactly("A");
            assertThat(result.stocksByIndustry().get("A"))
                    .extracting(StockRankingResponse::stockCode)
                    .containsExactly("001");
        }

        @Test
        @DisplayName("industryCode가 null인 종목은 제외한다")
        void excludesStocksWithNullIndustryCode() {
            List<StockRankingResponse> allStocks = List.of(
                    stock("001", 100),
                    stock("002", 500));
            Map<String, Stock> stockMap = new HashMap<>();
            stockMap.put("001", stockEntity("001", "A"));
            stockMap.put("002", stockEntity("002", null)); // industryCode null

            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(allStocks, stockMap);

            assertThat(result.sortedIndustryCodes()).containsExactly("A");
            assertThat(result.stocksByIndustry()).doesNotContainKey("UNKNOWN");
        }

        @Test
        @DisplayName("빈 입력이면 빈 결과를 돌려준다")
        void returnsEmptyForEmptyInput() {
            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(List.of(), Map.of());

            assertThat(result.sortedIndustryCodes()).isEmpty();
            assertThat(result.stocksByIndustry()).isEmpty();
        }

        @Test
        @DisplayName("거래대금 합계가 같은 업종의 순서는 정렬이 아니라 HashMap 순회 순서가 정한다")
        void tieOrderIsDeterminedByHashMapIterationNotInsertion() {
            // 합계가 모두 100으로 동일하다. 정렬이 순서를 만들지 못하므로
            // groupingBy가 만든 HashMap의 버킷 배치가 그대로 드러난다.
            List<StockRankingResponse> allStocks = List.of(
                    stock("001", 100),
                    stock("002", 100),
                    stock("003", 100));
            Map<String, Stock> stockMap = stockMap(Map.of(
                    "001", "0001", "002", "0002", "003", "0003"));

            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(allStocks, stockMap);

            // 삽입 순서(0001,0002,0003)가 아니라 실측값이다.
            // groupingBy를 LinkedHashMap/TreeMap 버전으로 바꾸면 여기서 깨진다(위험 f9).
            assertThat(result.sortedIndustryCodes()).containsExactly("0002", "0003", "0001");
        }

        @Test
        @DisplayName("stocksByIndustry는 방어적 복사 없이 그대로 나온다")
        void exposesUnderlyingMapWithoutDefensiveCopy() {
            List<StockRankingResponse> allStocks = List.of(stock("001", 100));
            Map<String, Stock> stockMap = stockMap(Map.of("001", "A"));

            IndustryRankingCalculationService.IndustryGrouping result =
                    service.groupAndSort(allStocks, stockMap);

            // groupingBy가 만든 가변 HashMap이어야 한다. List.copyOf/unmodifiableMap으로
            // 감싸면 순회 순서가 달라질 수 있어 금지된다.
            assertThat(result.stocksByIndustry()).isInstanceOf(HashMap.class);
        }
    }

    @Nested
    @DisplayName("selectTopStocks - 업종별 상위 종목 선별")
    class SelectTopStocks {

        @Test
        @DisplayName("업종당 maxPerIndustry개까지만 고른다")
        void limitsStocksPerIndustry() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of("A", List.of(
                    stock("001", 500),
                    stock("002", 400),
                    stock("003", 300),
                    stock("004", 200),
                    stock("005", 100),
                    stock("006", 50)));

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A")), Map.of("A", industry("A", "업종A")), 5);

            // 뮤테이션 1(maxPerIndustry 5->4) 검출 지점
            assertThat(result).hasSize(1);
            assertThat(result.get(0).stocks()).hasSize(5);
            assertThat(result.get(0).stocks())
                    .extracting(StockRankingResponse::stockCode)
                    .containsExactly("001", "002", "003", "004", "005");
        }

        @Test
        @DisplayName("업종 안에서 거래대금 내림차순으로 종목을 정렬한다")
        void sortsStocksByAmountDescendingWithinIndustry() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of("A", List.of(
                    stock("001", 100),
                    stock("002", 900),
                    stock("003", 500)));

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A")),
                    Map.of("A", industry("A", "업종A")), 5);

            assertThat(result.get(0).stocks())
                    .extracting(StockRankingResponse::stockCode)
                    .containsExactly("002", "003", "001");
        }

        @Test
        @DisplayName("종목 정렬 기준은 거래량이 아니라 거래대금이다")
        void sortsByAmountNotVolume() {
            // 거래대금과 거래량의 대소가 반대인 픽스처
            Map<String, List<StockRankingResponse>> byIndustry = Map.of("A", List.of(
                    stock("001", 100, 999),
                    stock("002", 900, 111)));

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A")),
                    Map.of("A", industry("A", "업종A")), 5);

            // 뮤테이션 6(정렬키 amount->volume) 검출 지점
            assertThat(result.get(0).stocks())
                    .extracting(StockRankingResponse::stockCode)
                    .containsExactly("002", "001");
        }

        @Test
        @DisplayName("sortedIndustryCodes가 준 업종 순서를 그대로 따른다")
        void preservesGivenIndustryOrder() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of(
                    "A", List.of(stock("001", 100)),
                    "B", List.of(stock("002", 999)),
                    "C", List.of(stock("003", 500)));

            // 거래대금 순이라면 B,C,A지만 인자로 준 순서가 우선이다
            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("C", "A", "B")),
                    Map.of("A", industry("A", "업종A"),
                            "B", industry("B", "업종B"),
                            "C", industry("C", "업종C")), 5);

            assertThat(result)
                    .extracting(IndustryStockRankingResponse::industryCode)
                    .containsExactly("C", "A", "B");
        }

        @Test
        @DisplayName("업종 개수를 잘라내지 않고 전부 담는다")
        void returnsEveryRequestedIndustry() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of(
                    "A", List.of(stock("001", 300)),
                    "B", List.of(stock("002", 200)),
                    "C", List.of(stock("003", 100)));

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A", "B", "C")),
                    Map.of("A", industry("A", "업종A"),
                            "B", industry("B", "업종B"),
                            "C", industry("C", "업종C")), 5);

            // 뮤테이션 5(result 마지막 원소 드롭) 검출 지점
            assertThat(result).hasSize(3);
            assertThat(result)
                    .extracting(IndustryStockRankingResponse::industryCode)
                    .containsExactly("A", "B", "C");
        }

        @Test
        @DisplayName("industryMap에 없는 업종코드는 업종명이 null로 나간다")
        void leavesIndustryNameNullWhenNotFound() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of(
                    "A", List.of(stock("001", 100)));

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A")), Map.of(), 5);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).industryCode()).isEqualTo("A");
            assertThat(result.get(0).industryName()).isNull();
        }

        @Test
        @DisplayName("종목이 없는 업종은 결과에서 빠진다")
        void skipsIndustriesWithNoStocks() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of(
                    "A", List.of(stock("001", 100)),
                    "B", List.of());

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A", "B")),
                    Map.of("A", industry("A", "업종A"),
                            "B", industry("B", "업종B")), 5);

            assertThat(result)
                    .extracting(IndustryStockRankingResponse::industryCode)
                    .containsExactly("A");
        }

        // NPE 테스트 삭제: selectTopStocks가 IndustryGrouping을 통째로 받게 되면서
        // stocksByIndustry와 sortedIndustryCodes의 불일치 상태가 표현 불가능해졌다.
        // 이것이 🟡3 시그니처 축소의 목적이다.

        @Test
        @DisplayName("maxPerIndustry가 0이면 모든 업종이 결과에서 빠진다")
        void returnsEmptyWhenMaxPerIndustryIsZero() {
            Map<String, List<StockRankingResponse>> byIndustry = Map.of(
                    "A", List.of(stock("001", 100)));

            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(byIndustry, List.of("A")), Map.of("A", industry("A", "업종A")), 0);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("업종 목록이 비면 빈 결과를 돌려준다")
        void returnsEmptyForEmptyIndustryList() {
            List<IndustryStockRankingResponse> result =
                    service.selectTopStocks(
                    new IndustryRankingCalculationService.IndustryGrouping(Map.of(), List.of()), Map.of(), 5);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("groupAndSort -> selectTopStocks 연결")
    class Pipeline {

        @Test
        @DisplayName("groupAndSort 결과를 그대로 넘기면 업종 순서가 보존된다")
        void endToEndPreservesIndustryOrder() {
            List<StockRankingResponse> allStocks = List.of(
                    stock("001", 100),
                    stock("002", 900),
                    stock("003", 500));
            Map<String, Stock> stockMap = stockMap(Map.of(
                    "001", "A", "002", "B", "003", "C"));

            IndustryRankingCalculationService.IndustryGrouping grouping =
                    service.groupAndSort(allStocks, stockMap);
            List<IndustryStockRankingResponse> result = service.selectTopStocks(
                    grouping,
                    Map.of("A", industry("A", "업종A"),
                            "B", industry("B", "업종B"),
                            "C", industry("C", "업종C")),
                    5);

            assertThat(result)
                    .extracting(IndustryStockRankingResponse::industryCode)
                    .containsExactly("B", "C", "A");
            assertThat(result)
                    .extracting(IndustryStockRankingResponse::industryName)
                    .containsExactly("업종B", "업종C", "업종A");
        }
    }
}
