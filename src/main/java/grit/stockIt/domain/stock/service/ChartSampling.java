package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.KisMinuteChartDataDto;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 차트 데이터 정규화·솎아내기 순수 로직.
 * KIS 원시 분봉 DTO의 중복 제거와 java.time 값 기반 표본 선택이라는 두 책임을 함께 안는다.
 */
@Component
public class ChartSampling {

    /**
     * 날짜+시간을 키로 중복을 제거한 뒤 날짜+시간 순으로 정렬한다.
     * 정렬 비교자는 time에 null 방어가 없으므로, 같은 날짜에 time이 null인 항목이
     * 둘 이상의 항목과 함께 남으면 NullPointerException이 난다.
     */
    public List<KisMinuteChartDataDto> deduplicateAndSort(List<KisMinuteChartDataDto> data) {
        Set<String> seen = new LinkedHashSet<>();
        List<KisMinuteChartDataDto> unique = new ArrayList<>();

        for (KisMinuteChartDataDto item : data) {
            String key = item.date() + "_" + item.time();
            if (!seen.contains(key)) {
                seen.add(key);
                unique.add(item);
            }
        }

        return unique.stream()
                .sorted(Comparator
                        .comparing(KisMinuteChartDataDto::date)
                        .thenComparing(KisMinuteChartDataDto::time))
                .collect(Collectors.toList());
    }

    /**
     * 직전에 채택한 분봉과 날짜가 다르거나 minuteInterval - 1분을 초과해 경과했으면 채택한다.
     */
    public boolean shouldKeepMinuteBar(LocalDateTime lastKept, LocalDateTime current, int minuteInterval) {
        return lastKept == null
                || !current.toLocalDate().equals(lastKept.toLocalDate())
                || current.isAfter(lastKept.plusMinutes(minuteInterval - 1L));
    }

    /**
     * 오름차순 정렬된 날짜 목록에서 7일 간격 표본의 인덱스를 고른다.
     * 입력이 오름차순임은 호출자가 보장한다.
     */
    public List<Integer> selectWeeklySampleIndexes(List<LocalDate> ascendingDates) {
        List<Integer> selected = new ArrayList<>();
        LocalDate lastSelectedDate = null;

        for (int i = 0; i < ascendingDates.size(); i++) {
            LocalDate currentDate = ascendingDates.get(i);

            boolean isLastItem = (i == ascendingDates.size() - 1);
            if (shouldKeepDailyBar(lastSelectedDate, currentDate, isLastItem)) {
                selected.add(i);
                lastSelectedDate = currentDate;
            }
        }

        return selected;
    }

    private boolean shouldKeepDailyBar(LocalDate lastSelectedDate, LocalDate currentDate, boolean isLastItem) {
        return lastSelectedDate == null
                || currentDate.isAfter(lastSelectedDate.plusDays(6))
                || isLastItem;
    }
}
