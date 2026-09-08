package grit.stockIt.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;

@DisplayName("KisValueParser 단위 특성화 테스트")
class KisValueParserTest {

    private final KisValueParser parser = new KisValueParser();

    private Logger parserLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        parserLogger = (Logger) LoggerFactory.getLogger("grit.stockIt.domain.stock.service.KisValueParser");
        appender = new ListAppender<>();
        appender.start();
        parserLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        parserLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 날짜는_yyyyMMdd로_파싱된다() {
        assertThat(parser.parseDate("20260903")).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "2026-09-03", "20261332", "202609031"})
    void 날짜_파싱_실패는_IllegalArgumentException으로_던진다(String dateStr) {
        assertThatThrownBy(() -> parser.parseDate(dateStr))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid date format: " + dateStr + " (expected: yyyyMMdd)")
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void 날짜를_yyyyMMdd_문자열로_포맷한다() {
        assertThat(parser.formatDate(LocalDate.of(2026, 9, 3))).isEqualTo("20260903");
    }

    @Test
    void 시간은_HHMMSS로_파싱된다() {
        assertThat(parser.parseTime("093000")).isEqualTo(LocalTime.of(9, 30, 0));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "9999", "0930000"})
    void 길이_가드에_걸린_시간은_로그_없이_null이다(String timeStr) {
        assertThat(parser.parseTime(timeStr)).isNull();
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "09x000"})
    void 시간_파싱_예외는_WARN_로그를_남기고_null을_반환한다(String timeStr) {
        assertThat(parser.parseTime(timeStr)).isNull();

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getLoggerName()).isEqualTo("grit.stockIt.domain.stock.service.KisValueParser");
        assertThat(event.getFormattedMessage()).isEqualTo("시간 파싱 실패: " + timeStr);
    }

    @Test
    void 정수는_공백을_제거하고_파싱되며_박싱_타입을_반환한다() {
        assertThat(parser.parseIntValue(" 70000 ")).isEqualTo(70000);
        assertThat(parser.parseIntValue("70000")).isInstanceOf(Integer.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 비어있는_정수_입력은_0이다(String value) {
        assertThat(parser.parseIntValue(value)).isEqualTo(0);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void 숫자가_아닌_정수_입력은_0이며_WARN_로그를_남긴다() {
        assertThat(parser.parseIntValue("abc")).isEqualTo(0);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("Integer 파싱 실패: abc");
    }

    @Test
    void 정수_범위를_넘는_값도_NumberFormatException이라_0이다() {
        assertThat(parser.parseIntValue("99999999999")).isEqualTo(0);
    }

    @Test
    void Long은_공백을_제거하고_파싱되며_박싱_타입을_반환한다() {
        assertThat(parser.parseLongValue(" 84000000 ")).isEqualTo(84000000L);
        assertThat(parser.parseLongValue("84000000")).isInstanceOf(Long.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void 비어있는_Long_입력은_0L이다(String value) {
        assertThat(parser.parseLongValue(value)).isEqualTo(0L);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void 숫자가_아닌_Long_입력은_0L이며_WARN_로그를_남긴다() {
        assertThat(parser.parseLongValue("abc")).isEqualTo(0L);
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).isEqualTo("Long 파싱 실패: abc");
    }
}
