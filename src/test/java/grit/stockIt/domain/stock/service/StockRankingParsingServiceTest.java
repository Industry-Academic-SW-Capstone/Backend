package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.KisRankingResponse;
import grit.stockIt.domain.stock.dto.KisStockDataDto;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StockRankingParsingService 순수 단위 테스트.
 *
 * 협력자가 없으므로 목도 스프링 컨텍스트도 없이 new로 만들어 호출한다.
 * 여기서 고정하는 것은 "KIS 와이어 포맷 해석 규칙"이다. 현재 동작에 결함으로
 * 분류된 것들(trim 비대칭, 등락 거래대금 0, 비정형 output시 빈 리스트)도
 * 이번 사이클에서는 고치지 않기로 했으므로 실측값 그대로 고정한다.
 */
class StockRankingParsingServiceTest {

    private final StockRankingParsingService service = new StockRankingParsingService();

    // ---------- 픽스처 헬퍼 ----------

    private static Map<String, Object> row(String... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static KisRankingResponse response(Object output) {
        return new KisRankingResponse("0", "0", "정상처리 되었습니다.", output);
    }

    @Nested
    @DisplayName("parseAmountRankingResponse - 거래대금 순위 파싱")
    class ParseAmountRanking {

        @Test
        @DisplayName("거래대금 랭킹은 종목코드로 mksc_shrn_iscd를 쓴다")
        void usesMkscShrnIscdAsStockCode() {
            Object output = List.of(row(
                    "mksc_shrn_iscd", "005930",
                    "hts_kor_isnm", "삼성전자",
                    "stck_prpr", "70000",
                    "acml_vol", "1000",
                    "acml_tr_pbmn", "70000000"));

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(output), 10);

            // 뮤테이션 2(mksc/stck 키 교환) 검출 지점
            assertThat(result).hasSize(1);
            assertThat(result.get(0).stockCode()).isEqualTo("005930");
            assertThat(result.get(0).stockName()).isEqualTo("삼성전자");
        }

        @Test
        @DisplayName("두 종목코드 키가 다 있으면 mksc_shrn_iscd를 우선한다")
        void prefersMkscOverStckWhenBothPresent() {
            // 두 키가 동시에 있어야 우선순위가 드러난다. 한쪽만 있는 픽스처로는
            // 삼항 연산자의 방향을 뒤집어도 결과가 같아 검출되지 않는다.
            Object output = List.of(row(
                    "mksc_shrn_iscd", "005930",
                    "stck_shrn_iscd", "999999"));

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(output), 10);

            assertThat(result.get(0).stockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("mksc_shrn_iscd가 없으면 stck_shrn_iscd로 넘어간다")
        void fallsBackToStckShrnIscd() {
            Object output = List.of(row(
                    "stck_shrn_iscd", "000660",
                    "hts_kor_isnm", "SK하이닉스"));

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(output), 10);

            assertThat(result.get(0).stockCode()).isEqualTo("000660");
        }

        @Test
        @DisplayName("limit만큼만 잘라서 돌려준다")
        void appliesLimit() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                rows.add(row("mksc_shrn_iscd", String.format("%06d", i)));
            }

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(rows), 3);

            assertThat(result).hasSize(3);
            assertThat(result)
                    .extracting(StockRankingResponse::stockCode)
                    .containsExactly("000000", "000001", "000002");
        }

        @Test
        @DisplayName("limit이 데이터 개수보다 크면 있는 만큼만 돌려준다")
        void limitLargerThanDataReturnsAll() {
            Object output = List.of(row("mksc_shrn_iscd", "005930"));

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(output), 30);

            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("marketType은 파싱 단계에서 UNKNOWN 임시값으로 채운다")
        void fillsMarketTypeWithUnknownPlaceholder() {
            Object output = List.of(row("mksc_shrn_iscd", "005930"));

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(output), 10);

            // DB 조회 후 withMarketType으로 교체되는 자리다
            assertThat(result.get(0).marketType()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("output이 Map이면 data 키 안의 리스트를 읽는다")
        void readsDataKeyWhenOutputIsMap() {
            Object output = Map.of("data", List.of(row("mksc_shrn_iscd", "005930")));

            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(output), 10);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).stockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("output이 비정형이면 예외 대신 빈 리스트가 나온다")
        void returnsEmptyForUnexpectedOutputShape() {
            // 결함 #9로 분류됐지만 이번 사이클에서 고치지 않는다
            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response("이상한문자열"), 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("output이 null이면 빈 리스트가 나온다")
        void returnsEmptyForNullOutput() {
            List<StockRankingResponse> result =
                    service.parseAmountRankingResponse(response(null), 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("파싱 중 예외가 나면 '응답 파싱 실패'로 감싼다")
        void wrapsParsingFailure() {
            // 리스트 원소가 Map이 아니면 매핑 단계에서 ClassCastException이 난다
            Object output = List.of("문자열원소");

            assertThatThrownBy(() -> service.parseAmountRankingResponse(response(output), 10))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("응답 파싱 실패")
                    .cause().isInstanceOf(ClassCastException.class);
        }
    }

    @Nested
    @DisplayName("parseFluctuationRankingResponse - 등락 순위 파싱")
    class ParseFluctuationRanking {

        @Test
        @DisplayName("등락 랭킹은 종목코드로 stck_shrn_iscd만 쓴다")
        void usesOnlyStckShrnIscd() {
            Object output = List.of(row(
                    "stck_shrn_iscd", "005930",
                    "hts_kor_isnm", "삼성전자"));

            List<StockRankingResponse> result =
                    service.parseFluctuationRankingResponse(response(output), 30);

            assertThat(result.get(0).stockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("등락 응답에 mksc_shrn_iscd가 있어도 무시한다")
        void ignoresMkscShrnIscd() {
            Object output = List.of(row(
                    "mksc_shrn_iscd", "999999",
                    "stck_shrn_iscd", "005930"));

            List<StockRankingResponse> result =
                    service.parseFluctuationRankingResponse(response(output), 30);

            // 거래대금 랭킹과 반대 방향이다. 두 매퍼를 뒤바꿔 쓰면 여기서 깨진다.
            assertThat(result.get(0).stockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("등락 응답에 거래대금이 없으면 0이 된다")
        void amountDefaultsToZero() {
            Object output = List.of(row("stck_shrn_iscd", "005930"));

            List<StockRankingResponse> result =
                    service.parseFluctuationRankingResponse(response(output), 30);

            // 결함 #7(/fluctuations amount 항상 0)의 발생 지점이다. 동결 대상.
            assertThat(result.get(0).amount()).isZero();
        }

        @Test
        @DisplayName("limit만큼만 잘라서 돌려준다")
        void appliesLimit() {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                rows.add(row("stck_shrn_iscd", String.format("%06d", i)));
            }

            List<StockRankingResponse> result =
                    service.parseFluctuationRankingResponse(response(rows), 4);

            assertThat(result).hasSize(4);
        }

        @Test
        @DisplayName("파싱 중 예외가 나면 '응답 파싱 실패(등락)'로 감싼다")
        void wrapsParsingFailureWithFluctuationSuffix() {
            Object output = List.of("문자열원소");

            assertThatThrownBy(() -> service.parseFluctuationRankingResponse(response(output), 30))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("응답 파싱 실패(등락)");
        }
    }

    @Nested
    @DisplayName("extractOutputMapList - output 구조 해석")
    class ExtractOutputMapList {

        @Test
        @DisplayName("List면 그대로 돌려준다")
        void returnsListAsIs() {
            List<Map<String, Object>> input = List.of(row("a", "1"));

            assertThat(service.extractOutputMapList(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("Map이면 data 키를 꺼낸다")
        void extractsDataKeyFromMap() {
            List<Map<String, Object>> inner = List.of(row("a", "1"));

            assertThat(service.extractOutputMapList(Map.of("data", inner))).isEqualTo(inner);
        }

        @Test
        @DisplayName("Map인데 data가 리스트가 아니면 빈 리스트를 돌려준다")
        void returnsEmptyWhenDataIsNotList() {
            assertThat(service.extractOutputMapList(Map.of("data", "리스트아님"))).isEmpty();
        }

        @Test
        @DisplayName("Map인데 data 키가 없으면 빈 리스트를 돌려준다")
        void returnsEmptyWhenDataKeyAbsent() {
            assertThat(service.extractOutputMapList(Map.of("other", "값"))).isEmpty();
        }

        @Test
        @DisplayName("null이면 빈 리스트를 돌려준다")
        void returnsEmptyForNull() {
            assertThat(service.extractOutputMapList(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("parseOutputData - output을 KisStockDataDto로")
    class ParseOutputData {

        @Test
        @DisplayName("List면 각 원소를 매핑한다")
        void mapsEachElementOfList() {
            Object output = List.of(
                    row("mksc_shrn_iscd", "005930"),
                    row("mksc_shrn_iscd", "000660"));

            List<KisStockDataDto> result = service.parseOutputData(output);

            assertThat(result)
                    .extracting(KisStockDataDto::stockCode)
                    .containsExactly("005930", "000660");
        }

        @Test
        @DisplayName("Map이면 data 키 안의 리스트를 매핑한다")
        void mapsDataKeyWhenMap() {
            Object output = Map.of("data", List.of(row("mksc_shrn_iscd", "005930")));

            assertThat(service.parseOutputData(output))
                    .extracting(KisStockDataDto::stockCode)
                    .containsExactly("005930");
        }

        @Test
        @DisplayName("비정형이면 빈 리스트를 돌려준다")
        void returnsEmptyForUnexpectedShape() {
            assertThat(service.parseOutputData(42)).isEmpty();
        }

        @Test
        @DisplayName("Map인데 data가 리스트가 아니면 빈 리스트를 돌려준다")
        void returnsEmptyWhenDataIsNotList() {
            assertThat(service.parseOutputData(Map.of("data", "리스트아님"))).isEmpty();
        }

        @Test
        @DisplayName("Map인데 data 키가 없으면 빈 리스트를 돌려준다")
        void returnsEmptyWhenDataKeyAbsent() {
            assertThat(service.parseOutputData(Map.of("other", "값"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("mapToKisStockDataDto / Fluctuation - 행 매핑")
    class RowMapping {

        @Test
        @DisplayName("거래대금용 매퍼는 9개 필드를 그대로 옮긴다")
        void amountMapperCopiesAllFields() {
            KisStockDataDto dto = service.mapToKisStockDataDto(row(
                    "mksc_shrn_iscd", "005930",
                    "hts_kor_isnm", "삼성전자",
                    "data_rank", "1",
                    "stck_prpr", "70000",
                    "prdy_vrss_sign", "2",
                    "prdy_vrss", "1000",
                    "prdy_ctrt", "1.45",
                    "acml_vol", "12345",
                    "acml_tr_pbmn", "999999"));

            assertThat(dto.stockCode()).isEqualTo("005930");
            assertThat(dto.stockName()).isEqualTo("삼성전자");
            assertThat(dto.rank()).isEqualTo("1");
            assertThat(dto.currentPrice()).isEqualTo("70000");
            assertThat(dto.changeSign()).isEqualTo("2");
            assertThat(dto.changeAmount()).isEqualTo("1000");
            assertThat(dto.changeRate()).isEqualTo("1.45");
            assertThat(dto.volume()).isEqualTo("12345");
            assertThat(dto.amount()).isEqualTo("999999");
        }

        @Test
        @DisplayName("거래대금 키가 없으면 문자열 '0'을 채운다")
        void amountDefaultsToStringZero() {
            KisStockDataDto dto = service.mapToKisStockDataDto(row("mksc_shrn_iscd", "005930"));

            assertThat(dto.amount()).isEqualTo("0");
        }

        @Test
        @DisplayName("거래대금용 매퍼는 두 키가 다 있으면 mksc_shrn_iscd를 고른다")
        void amountMapperPrefersMkscWhenBothPresent() {
            // 뮤테이션 2(mksc/stck 키 교환) 검출 지점
            KisStockDataDto dto = service.mapToKisStockDataDto(row(
                    "mksc_shrn_iscd", "005930",
                    "stck_shrn_iscd", "999999"));

            assertThat(dto.stockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("등락용 매퍼는 stck_shrn_iscd만 종목코드로 본다")
        void fluctuationMapperUsesStckShrnIscdOnly() {
            KisStockDataDto dto = service.mapToKisStockDataDtoFluctuation(row(
                    "mksc_shrn_iscd", "999999",
                    "stck_shrn_iscd", "005930"));

            assertThat(dto.stockCode()).isEqualTo("005930");
        }

        @Test
        @DisplayName("종목코드가 없으면 null인 채로 나간다")
        void leavesStockCodeNullWhenAbsent() {
            // 걸러내는 책임은 호출자에게 있다(결함 #10, 동결)
            assertThat(service.mapToKisStockDataDto(row("hts_kor_isnm", "이름만")).stockCode())
                    .isNull();
        }
    }

    @Nested
    @DisplayName("mapKisDataToStockRankingResponse - DTO 변환")
    class MapKisDataToResponse {

        @Test
        @DisplayName("문자열 숫자를 타입에 맞게 변환한다")
        void convertsStringNumbers() {
            KisStockDataDto dto = new KisStockDataDto(
                    "005930", "삼성전자", "1", "70000", "2", "1000", "1.45", "12345", "999999");

            StockRankingResponse result = service.mapKisDataToStockRankingResponse(dto);

            assertThat(result.volume()).isEqualTo(12345L);
            assertThat(result.amount()).isEqualTo(999999L);
            assertThat(result.currentPrice()).isEqualTo(70000);
            assertThat(result.changeAmount()).isEqualTo(1000);
            assertThat(result.changeRate()).isEqualTo("1.45");
            assertThat(result.changeSign()).isEqualTo(StockRankingResponse.PriceChangeSign.RISE);
        }

        @Test
        @DisplayName("등락 부호가 null이면 STEADY가 된다")
        void nullSignBecomesSteady() {
            KisStockDataDto dto = new KisStockDataDto(
                    "005930", "삼성전자", "1", "70000", null, "0", "0.0", "0", "0");

            assertThat(service.mapKisDataToStockRankingResponse(dto).changeSign())
                    .isEqualTo(StockRankingResponse.PriceChangeSign.STEADY);
        }

        @Test
        @DisplayName("알 수 없는 등락 부호도 STEADY가 된다")
        void unknownSignBecomesSteady() {
            KisStockDataDto dto = new KisStockDataDto(
                    "005930", "삼성전자", "1", "70000", "9", "0", "0.0", "0", "0");

            assertThat(service.mapKisDataToStockRankingResponse(dto).changeSign())
                    .isEqualTo(StockRankingResponse.PriceChangeSign.STEADY);
        }
    }

    @Nested
    @DisplayName("parseLongValue / parseIntValue - 숫자 파싱 비대칭")
    class NumberParsing {

        @Test
        @DisplayName("parseLongValue는 문자열 숫자를 변환한다")
        void parsesLongFromString() {
            assertThat(service.parseLongValue("12345")).isEqualTo(12345L);
        }

        @Test
        @DisplayName("parseLongValue는 Number를 longValue로 변환한다")
        void parsesLongFromNumber() {
            // Jackson이 숫자로 역직렬화한 경우를 위한 분기다
            assertThat(service.parseLongValue(42)).isEqualTo(42L);
            assertThat(service.parseLongValue(42L)).isEqualTo(42L);
            assertThat(service.parseLongValue(42.9)).isEqualTo(42L);
        }

        @Test
        @DisplayName("parseLongValue는 null이면 0")
        void parseLongNullIsZero() {
            assertThat(service.parseLongValue(null)).isZero();
        }

        @Test
        @DisplayName("parseLongValue는 숫자가 아닌 문자열이면 0")
        void parseLongInvalidStringIsZero() {
            assertThat(service.parseLongValue("숫자아님")).isZero();
        }

        @Test
        @DisplayName("parseLongValue는 String도 Number도 아니면 0")
        void parseLongUnsupportedTypeIsZero() {
            assertThat(service.parseLongValue(List.of("a"))).isZero();
        }

        @Test
        @DisplayName("parseLongValue는 공백을 다듬지 않아 0이 된다")
        void parseLongDoesNotTrim() {
            // parseIntValue와의 비대칭. 결함 #8로 분류됐으나 동결 대상이다.
            assertThat(service.parseLongValue(" 12345 ")).isZero();
        }

        @Test
        @DisplayName("parseIntValue는 공백을 다듬어서 파싱한다")
        void parseIntTrims() {
            assertThat(service.parseIntValue(" 70000 ")).isEqualTo(70000);
        }

        @Test
        @DisplayName("parseIntValue는 음수를 처리한다")
        void parseIntHandlesNegative() {
            assertThat(service.parseIntValue("-1500")).isEqualTo(-1500);
        }

        @Test
        @DisplayName("parseIntValue는 null이거나 공백뿐이면 0")
        void parseIntNullOrBlankIsZero() {
            assertThat(service.parseIntValue(null)).isZero();
            assertThat(service.parseIntValue("")).isZero();
            assertThat(service.parseIntValue("   ")).isZero();
        }

        @Test
        @DisplayName("parseIntValue는 숫자가 아니면 0")
        void parseIntInvalidIsZero() {
            assertThat(service.parseIntValue("숫자아님")).isZero();
        }
    }
}
