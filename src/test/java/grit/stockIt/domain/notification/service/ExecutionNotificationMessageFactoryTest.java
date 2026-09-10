package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExecutionNotificationMessageFactory} 단위 테스트.
 *
 * <p>협력자 0개다. Mockito 를 쓰지 않고 값만 넣어 값만 검사한다.
 * 이것이 순수 로직 추출의 목적이다 — 협력자 없이 경계값과 분기를 직접 검증할 수 있다.
 */
class ExecutionNotificationMessageFactoryTest {

    private static ExecutionFilledEvent event(String orderMethod, Integer quantity, String price) {
        return new ExecutionFilledEvent(
                1L, 2L, 3L, 100L, 4L, "테스트대회",
                "005930", "삼성전자",
                new BigDecimal(price), quantity, orderMethod);
    }

    @Nested
    @DisplayName("title: 종목명과 매매구분을 조합한다")
    class Title {

        @Test
        void buyProducesMaesu() {
            assertThat(ExecutionNotificationMessageFactory.title("삼성전자", "BUY"))
                    .isEqualTo("삼성전자 매수 체결");
        }

        @Test
        void sellProducesMaedo() {
            assertThat(ExecutionNotificationMessageFactory.title("삼성전자", "SELL"))
                    .isEqualTo("삼성전자 매도 체결");
        }

        /**
         * 경계: "BUY" 가 아닌 모든 값이 매도로 떨어진다. 현재 동작을 명시적으로 고정한다.
         */
        @ParameterizedTest
        @ValueSource(strings = {"SELL", "buy", "Buy", "", "UNKNOWN"})
        void nonBuyFallsBackToMaedo(String orderMethod) {
            assertThat(ExecutionNotificationMessageFactory.title("삼성전자", orderMethod))
                    .isEqualTo("삼성전자 매도 체결");
        }

        @Test
        void nullOrderMethodFallsBackToMaedo() {
            assertThat(ExecutionNotificationMessageFactory.title("삼성전자", null))
                    .isEqualTo("삼성전자 매도 체결");
        }
    }

    @Nested
    @DisplayName("body: 수량과 금액을 로케일 무관하게 포맷한다")
    class Body {

        @Test
        void formatsQuantityAndPrice() {
            assertThat(ExecutionNotificationMessageFactory.body("BUY", 10, new BigDecimal("70000")))
                    .isEqualTo("매수 10주가 70,000원에 체결되었습니다");
        }

        /**
         * 경계: 수량 1주. 단수/복수 구분이 없음을 고정한다.
         */
        @Test
        void quantityOne() {
            assertThat(ExecutionNotificationMessageFactory.body("BUY", 1, new BigDecimal("500")))
                    .isEqualTo("매수 1주가 500원에 체결되었습니다");
        }

        /**
         * 경계: 천 단위 구분자가 나타나기 시작하는 지점.
         */
        @ParameterizedTest
        @CsvSource({
                "999,    999",
                "1000,   '1,000'",
                "1001,   '1,001'",
                "999999, '999,999'",
                "1000000, '1,000,000'"
        })
        void thousandsSeparatorBoundary(String price, String expected) {
            assertThat(ExecutionNotificationMessageFactory.formatPrice(new BigDecimal(price)))
                    .isEqualTo(expected);
        }

        /**
         * 경계: 0원과 음수.
         */
        @Test
        void zeroPrice() {
            assertThat(ExecutionNotificationMessageFactory.formatPrice(BigDecimal.ZERO))
                    .isEqualTo("0");
        }

        @Test
        void negativePrice() {
            assertThat(ExecutionNotificationMessageFactory.formatPrice(new BigDecimal("-1000")))
                    .isEqualTo("-1,000");
        }

        /**
         * null 수량은 "null주가" 를 출력한다. 원시형 int 로 바꾸면 NPE 가 되므로
         * Integer 를 유지해야 이 동작이 보존된다.
         */
        @Test
        void nullQuantityPrintsNullLiteral() {
            assertThat(ExecutionNotificationMessageFactory.body("BUY", null, new BigDecimal("100")))
                    .isEqualTo("매수 null주가 100원에 체결되었습니다");
        }

        @Test
        void nullPriceThrows() {
            assertThatThrownBy(() -> ExecutionNotificationMessageFactory.body("BUY", 10, null))
                    .isInstanceOf(NullPointerException.class);
        }

        /**
         * 로케일 독립성. 기본 로케일이 ar-SA 여도 latn 숫자가 나와야 한다.
         * Locale.ROOT 고정이 풀리면 이 단정이 red 가 된다.
         */
        @Test
        void bodyIsLocaleIndependent() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));
                assertThat(ExecutionNotificationMessageFactory.body("BUY", 10, new BigDecimal("70000")))
                        .isEqualTo("매수 10주가 70,000원에 체결되었습니다");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }

        @Test
        void formatPriceIsLocaleIndependent() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("de-DE"));
                assertThat(ExecutionNotificationMessageFactory.formatPrice(new BigDecimal("1234567")))
                        .isEqualTo("1,234,567");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }
    }

    @Nested
    @DisplayName("detailMap: 10키, HashMap, 타임스탬프 없음")
    class DetailMap {

        @Test
        void hasExactly10Keys() {
            Map<String, Object> m = ExecutionNotificationMessageFactory.detailMap(event("BUY", 10, "70000"));

            assertThat(m).hasSize(10);
            assertThat(m.keySet()).containsExactlyInAnyOrder(
                    "executionId", "orderId", "accountId", "contestId", "contestName",
                    "stockCode", "stockName", "price", "quantity", "orderMethod");
        }

        /**
         * 맵 구현이 HashMap 이어야 한다. LinkedHashMap/Map.of 로 바뀌면 직렬화 JSON 순서가
         * 바뀌고 detailData 는 @JsonRawValue 로 API 응답에 그대로 실린다.
         */
        @Test
        void isHashMap() {
            assertThat(ExecutionNotificationMessageFactory.detailMap(event("BUY", 10, "70000")))
                    .isExactlyInstanceOf(HashMap.class);
        }

        @Test
        void hasNoTimestamp() {
            assertThat(ExecutionNotificationMessageFactory.detailMap(event("BUY", 10, "70000")))
                    .doesNotContainKeys("executedAt", "sentAt", "timestamp", "completedAt");
        }

        @Test
        void preservesRawTypes() {
            Map<String, Object> m = ExecutionNotificationMessageFactory.detailMap(event("BUY", 10, "70000"));

            assertThat(m.get("executionId")).isInstanceOf(Long.class);
            assertThat(m.get("quantity")).isInstanceOf(Integer.class);
            assertThat(m.get("price")).isInstanceOf(BigDecimal.class);
        }
    }

    @Nested
    @DisplayName("fcmData: 14키, 전부 문자열")
    class FcmData {

        private Map<String, String> data() {
            return ExecutionNotificationMessageFactory.fcmData(
                    event("BUY", 10, "70000"), "제목", "본문", 1700000000000L);
        }

        @Test
        void hasExactly14Keys() {
            assertThat(data()).hasSize(14);
            assertThat(data().keySet()).containsExactlyInAnyOrder(
                    "title", "body", "type",
                    "executionId", "orderId", "accountId", "contestId", "contestName",
                    "stockCode", "stockName", "price", "quantity", "orderMethod",
                    "executedAt");
        }

        @Test
        void isHashMap() {
            assertThat(data()).isExactlyInstanceOf(HashMap.class);
        }

        @Test
        void usesProvidedNowMillisNotSystemClock() {
            assertThat(data()).containsEntry("executedAt", "1700000000000");
        }

        @Test
        void titleAndBodyArePassedThrough() {
            assertThat(data()).containsEntry("title", "제목");
            assertThat(data()).containsEntry("body", "본문");
        }

        @Test
        void typeIsExecution() {
            assertThat(data()).containsEntry("type", "EXECUTION");
        }

        @Test
        void identifiersAreStringified() {
            assertThat(data()).containsEntry("executionId", "1");
            assertThat(data()).containsEntry("orderId", "2");
            assertThat(data()).containsEntry("accountId", "3");
            assertThat(data()).containsEntry("contestId", "4");
            assertThat(data()).containsEntry("quantity", "10");
            assertThat(data()).containsEntry("price", "70000");
        }
    }
}
