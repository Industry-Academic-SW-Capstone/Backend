package grit.stockIt.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

@DisplayName("ChartPeriodPolicy 단위 특성화 테스트")
class ChartPeriodPolicyTest {

    private final ChartPeriodPolicy policy = new ChartPeriodPolicy();

    @ParameterizedTest
    @CsvSource({"3MONTH,3month", "1Day,1day", "1week,1week", "FOO,foo"})
    void 기간_타입을_소문자로_정규화한다(String input, String expected) {
        assertThat(policy.normalize(input)).isEqualTo(expected);
    }

    @Test
    void 캐시_키는_prefix와_종목코드와_정규화_타입을_콜론으로_잇는다() {
        assertThat(policy.cacheKey("005930", "3month")).isEqualTo("stock:chart:005930:3month");
    }

    @ParameterizedTest
    @CsvSource({
            "1day,60", "day,60",
            "1week,300", "week,300",
            "3month,1800",
            "1year,3600", "year,3600",
            "5year,43200"
    })
    void 기간별_캐시_TTL을_반환한다(String normalizedType, long expectedSeconds) {
        assertThat(policy.cacheTtl(normalizedType)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foo", "10year", ""})
    void 지원하지_않는_타입의_TTL은_30분이다(String normalizedType) {
        assertThat(policy.cacheTtl(normalizedType)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void 기간_판정은_별칭까지_인정한다() {
        assertThat(policy.isOneDay("1day")).isTrue();
        assertThat(policy.isOneDay("day")).isTrue();
        assertThat(policy.isOneDay("1week")).isFalse();

        assertThat(policy.isOneWeek("1week")).isTrue();
        assertThat(policy.isOneWeek("week")).isTrue();
        assertThat(policy.isOneWeek("1day")).isFalse();

        assertThat(policy.isThreeMonth("3month")).isTrue();
        assertThat(policy.isThreeMonth("month")).isFalse();

        assertThat(policy.isOneYear("1year")).isTrue();
        assertThat(policy.isOneYear("year")).isTrue();
        assertThat(policy.isOneYear("5year")).isFalse();

        assertThat(policy.isFiveYear("5year")).isTrue();
        assertThat(policy.isFiveYear("year")).isFalse();
    }

    @Test
    void 미지원_기간_메시지는_전달받은_값을_그대로_에코한다() {
        assertThat(policy.unsupportedPeriodMessage("foo"))
                .isEqualTo("Invalid period type: foo. Supported: 1day, 1week, 3month, 1year, 5year");
    }
}
