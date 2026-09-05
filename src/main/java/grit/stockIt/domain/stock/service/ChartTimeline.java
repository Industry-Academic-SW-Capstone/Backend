package grit.stockIt.domain.stock.service;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * KIS 호출 시각·일자 산출 순수 로직.
 * 1일·1주·1년 조회의 KIS 호출 횟수를 결정하는 인자를 한곳에 모은다.
 */
@Component
public class ChartTimeline {

    private static final LocalTime MARKET_START = LocalTime.of(9, 0);
    private static final LocalTime MARKET_END = LocalTime.of(15, 30);
    private static final int WEEK_BUSINESS_DAY_COUNT = 5;
    private static final List<String> DAILY_MINUTE_WINDOWS = List.of("153000", "133000", "113000", "093000");

    public boolean isBeforeMarketOpen(LocalTime now) {
        return now.isBefore(MARKET_START);
    }

    /**
     * 장 시작 전이면 빈 리스트, 그 외에는 장 종료 시각으로 클램프한 조회 시작 시각 목록.
     */
    public List<String> intradayRequestTimes(LocalTime now) {
        if (isBeforeMarketOpen(now)) {
            return new ArrayList<>();
        }

        LocalTime endTime = now.isAfter(MARKET_END) ? MARKET_END : now;
        return calculateTimeRanges(MARKET_START, endTime);
    }

    /**
     * 시간 범위를 30분 단위로 나눈다. 말미 시각을 무조건 덧붙이므로 결과는 절대 비지 않는다.
     */
    public List<String> calculateTimeRanges(LocalTime start, LocalTime end) {
        List<String> ranges = new ArrayList<>();
        LocalTime current = start;

        while (current.isBefore(end) || current.equals(end)) {
            String timeStr = String.format(Locale.ROOT, "%02d%02d%02d", current.getHour(), current.getMinute(), 0);
            ranges.add(timeStr);

            current = current.plusMinutes(30);

            if (current.isAfter(end)) {
                break;
            }
        }

        String endTimeStr = String.format(Locale.ROOT, "%02d%02d%02d", end.getHour(), end.getMinute(), end.getSecond());
        if (ranges.isEmpty() || !ranges.get(ranges.size() - 1).equals(endTimeStr)) {
            ranges.add(endTimeStr);
        }

        return ranges;
    }

    /**
     * 조회 종료일 기준 최근 영업일 목록(주말만 제외하므로 공휴일은 영업일로 취급된다).
     */
    public List<LocalDate> recentBusinessDays(LocalDate endDate, int count) {
        List<LocalDate> result = new ArrayList<>();
        LocalDate cursor = endDate;

        while (result.size() < count) {
            DayOfWeek dayOfWeek = cursor.getDayOfWeek();
            if (dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY) {
                result.add(cursor);
            }
            cursor = cursor.minusDays(1);
        }

        Collections.reverse(result);
        return result;
    }

    public int weekBusinessDayCount() {
        return WEEK_BUSINESS_DAY_COUNT;
    }

    /**
     * KIS 조회 기간 제한 때문에 1년 구간을 6개월씩 두 조각으로 나눈다.
     */
    public List<DateRange> splitIntoHalves(LocalDate startDate, LocalDate endDate) {
        LocalDate midDate = startDate.plusMonths(6);
        return List.of(
                new DateRange(startDate, midDate),
                new DateRange(midDate.plusDays(1), endDate)
        );
    }

    public List<String> dailyMinuteWindows() {
        return DAILY_MINUTE_WINDOWS;
    }

    public record DateRange(LocalDate start, LocalDate end) { }
}
