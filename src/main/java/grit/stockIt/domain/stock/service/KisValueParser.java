package grit.stockIt.domain.stock.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * KIS API 응답 문자열을 값 타입으로 변환하는 순수 로직.
 * 실패 처리 방식이 필드마다 다르다: 날짜는 예외를 던지고, 시간은 null을 돌려주며,
 * 숫자는 NumberFormatException만 삼켜 0을 돌려준다.
 */
@Slf4j
@Component
public class KisValueParser {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    public LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date format: " + dateStr + " (expected: yyyyMMdd)", e);
        }
    }

    public LocalTime parseTime(String timeStr) {
        if (timeStr == null || timeStr.trim().isEmpty() || timeStr.length() != 6) {
            return null;
        }
        try {
            int hour = Integer.parseInt(timeStr.substring(0, 2));
            int minute = Integer.parseInt(timeStr.substring(2, 4));
            int second = Integer.parseInt(timeStr.substring(4, 6));
            return LocalTime.of(hour, minute, second);
        } catch (Exception e) {
            log.warn("시간 파싱 실패: {}", timeStr);
            return null;
        }
    }

    public Integer parseIntValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Integer 파싱 실패: {}", value);
            return 0;
        }
    }

    public Long parseLongValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Long 파싱 실패: {}", value);
            return 0L;
        }
    }

    public String formatDate(LocalDate date) {
        return date.format(DATE_FORMATTER);
    }
}
