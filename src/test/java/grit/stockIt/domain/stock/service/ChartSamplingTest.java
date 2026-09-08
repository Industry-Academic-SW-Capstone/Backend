package grit.stockIt.domain.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import grit.stockIt.domain.stock.dto.KisMinuteChartDataDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@DisplayName("ChartSampling 단위 특성화 테스트")
class ChartSamplingTest {

    private static final int MINUTE_INTERVAL = 10;

    private final ChartSampling sampling = new ChartSampling();

    private static KisMinuteChartDataDto bar(String date, String time) {
        return new KisMinuteChartDataDto(date, time, "70000", "69900", "70100", "69800", "1200", "84000000");
    }

    @Test
    void S1_직전_채택_분봉이_없으면_채택한다() {
        assertThat(sampling.shouldKeepMinuteBar(null, LocalDateTime.of(2026, 9, 3, 9, 0), MINUTE_INTERVAL)).isTrue();
    }

    @Test
    void S2_9분_미만_경과는_탈락이다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 3, 9, 0, 0),
                LocalDateTime.of(2026, 9, 3, 9, 8, 59),
                MINUTE_INTERVAL)).isFalse();
    }

    @Test
    void S3_정확히_9분_경과는_경계_배타로_탈락이다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 3, 9, 0, 0),
                LocalDateTime.of(2026, 9, 3, 9, 9, 0),
                MINUTE_INTERVAL)).isFalse();
    }

    @Test
    void S4_9분_30초_경과는_채택된다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 3, 9, 0, 0),
                LocalDateTime.of(2026, 9, 3, 9, 9, 30),
                MINUTE_INTERVAL)).isTrue();
    }

    @Test
    void S5_10분_경과는_채택된다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 3, 9, 0, 0),
                LocalDateTime.of(2026, 9, 3, 9, 10, 0),
                MINUTE_INTERVAL)).isTrue();
    }

    @Test
    void S6_날짜가_바뀌면_경과_시간과_무관하게_채택된다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 2, 15, 20),
                LocalDateTime.of(2026, 9, 3, 9, 0),
                MINUTE_INTERVAL)).isTrue();
    }

    @Test
    void S7_날짜가_바뀌면_시간이_더_이르지_않아도_채택된다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 2, 15, 20),
                LocalDateTime.of(2026, 9, 3, 15, 19),
                MINUTE_INTERVAL)).isTrue();
    }

    @Test
    void S8_간격이_1이면_임계가_0이라_같은_날_9분_30초도_채택된다() {
        assertThat(sampling.shouldKeepMinuteBar(
                LocalDateTime.of(2026, 9, 3, 9, 0, 0),
                LocalDateTime.of(2026, 9, 3, 9, 9, 30),
                1)).isTrue();
    }

    @Test
    void D0_빈_입력은_빈_인덱스_목록이다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of())).isEmpty();
    }

    @Test
    void D1_첫_원소는_항상_선택된다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 20))))
                .containsExactly(0, 1);
    }

    @Test
    void D2_6일_뒤_원소는_경계_배타로_탈락한다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 7),
                LocalDate.of(2026, 1, 20))))
                .containsExactly(0, 2);
    }

    @Test
    void D3_7일_뒤_원소는_선택된다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 8),
                LocalDate.of(2026, 1, 20))))
                .containsExactly(0, 1, 2);
    }

    @Test
    void D4_마지막_원소는_임계_미달이어도_포함된다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2))))
                .containsExactly(0, 1);
    }

    @Test
    void D5_단일_원소는_한_번만_방출된다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of(LocalDate.of(2026, 1, 1))))
                .containsExactly(0);
    }

    @Test
    void D6_중간_원소_탈락과_마지막_강제_포함이_함께_성립한다() {
        assertThat(sampling.selectWeeklySampleIndexes(List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 2),
                LocalDate.of(2026, 1, 3))))
                .containsExactly(0, 2);
    }

    @Test
    void U1_날짜와_시간이_같은_중복은_먼저_등장한_항목만_남는다() {
        KisMinuteChartDataDto first = bar("20260903", "090000");
        KisMinuteChartDataDto duplicate = new KisMinuteChartDataDto(
                "20260903", "090000", "71000", "70900", "71100", "70800", "1300", "85000000");

        List<KisMinuteChartDataDto> result = sampling.deduplicateAndSort(List.of(first, duplicate));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(first);
    }

    @Test
    void U2_역순_입력은_오름차순으로_정렬된다() {
        List<KisMinuteChartDataDto> result = sampling.deduplicateAndSort(List.of(
                bar("20260903", "150000"),
                bar("20260903", "110000"),
                bar("20260902", "150000")));

        assertThat(result).extracting(KisMinuteChartDataDto::date, KisMinuteChartDataDto::time)
                .containsExactly(
                        tuple("20260902", "150000"),
                        tuple("20260903", "110000"),
                        tuple("20260903", "150000"));
    }

    @Test
    void U3_빈_입력은_빈_리스트다() {
        assertThat(sampling.deduplicateAndSort(List.of())).isEmpty();
    }

    @Test
    void U4_같은_날짜에_시간이_null인_항목이_섞이면_NullPointerException이_난다() {
        List<KisMinuteChartDataDto> data = List.of(
                bar("20260903", "090000"),
                bar("20260903", null));

        assertThatThrownBy(() -> sampling.deduplicateAndSort(data))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void U5_같은_날짜의_다른_시간은_둘_다_유지된다() {
        assertThat(sampling.deduplicateAndSort(List.of(
                bar("20260903", "090000"),
                bar("20260903", "091000"))))
                .hasSize(2);
    }

    @Test
    void U6_반환된_리스트는_가변이다() {
        List<KisMinuteChartDataDto> result = sampling.deduplicateAndSort(List.of(bar("20260903", "090000")));

        result.add(bar("20260903", "091000"));

        assertThat(result).hasSize(2);
    }

    @Test
    void U7_시간이_null인_항목이_하나뿐이면_비교가_없어_NPE가_나지_않는다() {
        List<KisMinuteChartDataDto> result = sampling.deduplicateAndSort(List.of(bar("20260903", null)));

        assertThat(result).hasSize(1);
    }
}
