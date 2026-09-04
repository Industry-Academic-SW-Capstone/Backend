package grit.stockIt.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@DisplayName("ChartTimeline 단위 특성화 테스트")
class ChartTimelineTest {

    private static final List<String> FULL_GRID = List.of(
            "090000", "093000", "100000", "103000", "110000", "113000", "120000",
            "123000", "130000", "133000", "140000", "143000", "150000", "153000");

    private final ChartTimeline timeline = new ChartTimeline();

    @Test
    void T1_장_시작_1분_전에는_조회_시각이_없다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(8, 59, 0))).isEmpty();
    }

    @Test
    void T2_장_시작_1초_전에도_조회_시각이_없다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(8, 59, 59))).isEmpty();
    }

    @Test
    void T3_장_시작_정각에는_한_건이다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(9, 0, 0)))
                .containsExactly("090000");
    }

    @Test
    void T4_장_시작_1초_뒤에는_말미_실초가_추가된다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(9, 0, 1)))
                .containsExactly("090000", "090001");
    }

    @Test
    void T5_장중_임의_시각은_30분_격자와_말미_실초를_함께_돌려준다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(10, 15, 37)))
                .containsExactly("090000", "093000", "100000", "101537");
    }

    @Test
    void T6_장_종료_정각은_30분_격자_14건이다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(15, 30, 0)))
                .containsExactlyElementsOf(FULL_GRID);
    }

    @Test
    void T7_장_종료_직후는_종료_시각으로_클램프된다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(15, 31, 0)))
                .containsExactlyElementsOf(FULL_GRID);
    }

    @Test
    void T8_자정_직전도_종료_시각으로_클램프된다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.of(23, 59, 59)))
                .containsExactlyElementsOf(FULL_GRID);
    }

    @Test
    void T9_자정에는_조회_시각이_없다() {
        assertThat(timeline.intradayRequestTimes(LocalTime.MIDNIGHT)).isEmpty();
    }

    @Test
    void T10_시작이_종료보다_늦어도_calculateTimeRanges는_비지_않는다() {
        assertThat(timeline.calculateTimeRanges(LocalTime.of(9, 0), LocalTime.of(8, 0)))
                .containsExactly("080000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00:00", "03:17:42", "08:59:59"})
    void 장_시작_전_임의_시각은_공집합이다(String now) {
        assertThat(timeline.intradayRequestTimes(LocalTime.parse(now))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:00:00", "12:34:56", "23:59:59"})
    void 장_시작_이후_임의_시각은_공집합이_아니다(String now) {
        assertThat(timeline.intradayRequestTimes(LocalTime.parse(now))).isNotEmpty();
    }

    @Test
    void 장_시작_판정은_09시_정각을_경계로_배타적이다() {
        assertThat(timeline.isBeforeMarketOpen(LocalTime.of(8, 59, 59))).isTrue();
        assertThat(timeline.isBeforeMarketOpen(LocalTime.of(9, 0, 0))).isFalse();
        assertThat(timeline.isBeforeMarketOpen(LocalTime.of(15, 31))).isFalse();
    }

    @Test
    void W1_목요일_기준_최근_5영업일() {
        assertThat(timeline.recentBusinessDays(LocalDate.of(2026, 9, 3), 5))
                .containsExactly(
                        LocalDate.of(2026, 8, 28),
                        LocalDate.of(2026, 8, 31),
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 3));
    }

    @Test
    void W2_일요일_기준도_영업일_5건이며_주말을_제외한다() {
        List<LocalDate> days = timeline.recentBusinessDays(LocalDate.of(2026, 9, 6), 5);

        assertThat(days).hasSize(5);
        assertThat(days).containsExactly(
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 2),
                LocalDate.of(2026, 9, 3),
                LocalDate.of(2026, 9, 4));
    }

    @Test
    void W3_토요일_기준도_영업일_5건이다() {
        List<LocalDate> days = timeline.recentBusinessDays(LocalDate.of(2026, 9, 5), 5);

        assertThat(days).hasSize(5);
        assertThat(days).doesNotContain(LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 6));
    }

    @Test
    void W4_일별_분봉_조회_윈도는_4건이다() {
        assertThat(timeline.dailyMinuteWindows())
                .containsExactly("153000", "133000", "113000", "093000");
    }

    @Test
    void W5_공휴일은_영업일로_취급된다() {
        assertThat(timeline.recentBusinessDays(LocalDate.of(2026, 1, 2), 5))
                .contains(LocalDate.of(2026, 1, 1));
    }

    @Test
    void W6_1주_조회의_KIS_호출_수는_영업일_수_곱하기_윈도_수다() {
        int calls = timeline.recentBusinessDays(LocalDate.of(2026, 9, 3), timeline.weekBusinessDayCount()).size()
                * timeline.dailyMinuteWindows().size();

        assertThat(calls).isEqualTo(20);
    }

    @Test
    void 영업일_수는_5다() {
        assertThat(timeline.weekBusinessDayCount()).isEqualTo(5);
    }

    @Test
    void 일년_구간은_6개월씩_두_조각으로_나뉜다() {
        assertThat(timeline.splitIntoHalves(LocalDate.of(2025, 9, 3), LocalDate.of(2026, 9, 3)))
                .containsExactly(
                        new ChartTimeline.DateRange(LocalDate.of(2025, 9, 3), LocalDate.of(2026, 3, 3)),
                        new ChartTimeline.DateRange(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 9, 3)));
    }

    @Test
    void 윤년_시작일도_두_조각으로_나뉜다() {
        assertThat(timeline.splitIntoHalves(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 28)))
                .containsExactly(
                        new ChartTimeline.DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 8, 29)),
                        new ChartTimeline.DateRange(LocalDate.of(2024, 8, 30), LocalDate.of(2025, 2, 28)));
    }

    @Test
    void 임의_시작일_30건에서도_조각은_항상_2개다() {
        LocalDate start = LocalDate.of(2020, 1, 1);
        for (int i = 0; i < 30; i++) {
            LocalDate from = start.plusDays(i * 13L);
            assertThat(timeline.splitIntoHalves(from, from.plusYears(1))).hasSize(2);
        }
    }
}
