package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.KisChartDataDto;
import grit.stockIt.domain.stock.dto.KisChartResponseDto;
import grit.stockIt.domain.stock.dto.KisMinuteChartDataDto;
import grit.stockIt.domain.stock.dto.StockChartDto;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 주식 차트 데이터 조회 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockChartService {

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 주식 차트 데이터 조회
     * @param stockCode 종목코드 (6자리)
     * @param periodType 기간 타입 (1day/1week/3month/1year/5year)
     * @return 차트 데이터 리스트
     */
    public Mono<List<StockChartDto>> getStockChart(
            String stockCode,
            String periodType
    ) {
        log.info("주식 차트 데이터 조회 요청: {} - periodType={}", stockCode, periodType);

        String normalizedType = periodType.toLowerCase();
        
        // 1일 - 1분 간격 (390개)
        if ("1day".equals(normalizedType) || "day".equals(normalizedType)) {
            return getMinuteChartDataFromKisMultiple(stockCode, 1) // 1분 간격
                    .map(chartDataList -> {
                        return chartDataList.stream()
                                .map(kisData -> mapMinuteToStockChartDto(stockCode, periodType, kisData))
                                .toList();
                    })
                    .doOnError(e -> log.error("주식 분봉 데이터 조회 중 오류 발생: {}", stockCode, e))
                    .onErrorResume(e -> Mono.error(new RuntimeException("주식 분봉 데이터 조회 실패: " + stockCode, e)));
        }
        
        // 1주 - 10분 간격 (1주일 전부터 현재까지, 10분 간격)
        if ("1week".equals(normalizedType) || "week".equals(normalizedType)) {
            return getMinuteChartDataForWeek(stockCode, 10) // 10분 간격
                    .map(chartDataList -> {
                        // 10분 간격으로 필터링 (첫 번째 데이터부터 10분마다)
                        List<StockChartDto> result = new ArrayList<>();
                        LocalTime lastTime = null;
                        for (KisMinuteChartDataDto kisData : chartDataList) {
                            LocalTime currentTime = parseTime(kisData.time());
                            if (currentTime == null) continue;
                            
                            if (lastTime == null || 
                                currentTime.isAfter(lastTime.plusMinutes(9))) { // 10분 이상 차이
                                result.add(mapMinuteToStockChartDto(stockCode, periodType, kisData));
                                lastTime = currentTime;
                            }
                        }
                        return result;
                    })
                    .doOnError(e -> log.error("주식 분봉 데이터 조회 중 오류 발생: {}", stockCode, e))
                    .onErrorResume(e -> Mono.error(new RuntimeException("주식 분봉 데이터 조회 실패: " + stockCode, e)));
        }

        // 3달 - 1일 간격 (일봉)
        if ("3month".equals(normalizedType)) {
            LocalDate endDateLocal = LocalDate.now();
            LocalDate startDateLocal = endDateLocal.minusMonths(3);
            return getChartDataFromKis(stockCode, "D", startDateLocal, endDateLocal) // 일봉
                    .map(chartDataList -> {
                        return chartDataList.stream()
                                .map(kisData -> mapToStockChartDto(stockCode, periodType, kisData))
                                .toList();
                    })
                    .doOnError(e -> log.error("주식 차트 데이터 조회 중 오류 발생: {}", stockCode, e))
                    .onErrorResume(e -> Mono.error(new RuntimeException("주식 차트 데이터 조회 실패: " + stockCode, e)));
        }
        
        // 1년 - 7일 간격 (일봉, 7일 간격으로 필터링)
        if ("1year".equals(normalizedType) || "year".equals(normalizedType)) {
            LocalDate endDateLocal = LocalDate.now();
            LocalDate startDateLocal = endDateLocal.minusYears(1);
            
            // KIS API 제한으로 인해 여러 번 호출하여 합치기 (6개월씩 나눠서)
            List<Mono<List<KisChartDataDto>>> monos = new ArrayList<>();
            
            // 1년을 6개월씩 2번으로 나눠서 호출
            LocalDate midDate = startDateLocal.plusMonths(6);
            
            monos.add(getChartDataFromKis(stockCode, "D", startDateLocal, midDate)); // 첫 6개월
            monos.add(getChartDataFromKis(stockCode, "D", midDate.plusDays(1), endDateLocal)); // 나머지 6개월 (11월 포함)
            
            // 모든 Mono를 병렬로 실행하고 합치기
            return Flux.merge(monos)
                    .collectList()
                    .map(listOfLists -> {
                        // 모든 리스트를 하나로 합치기
                        List<KisChartDataDto> allData = new ArrayList<>();
                        for (List<KisChartDataDto> list : listOfLists) {
                            allData.addAll(list);
                        }
                        
                        // 날짜 기준 오름차순 정렬 (과거 → 현재)
                        List<KisChartDataDto> sortedList = allData.stream()
                                .sorted(Comparator.comparing(kisData -> parseDate(kisData.date())))
                                .toList();
                        
                        // 날짜 기준으로 7일 간격 필터링 (과거부터 현재까지)
                        List<StockChartDto> result = new ArrayList<>();
                        LocalDate lastSelectedDate = null;
                        
                        for (int i = 0; i < sortedList.size(); i++) {
                            KisChartDataDto kisData = sortedList.get(i);
                            LocalDate currentDate = parseDate(kisData.date());
                            
                            // 첫 번째 데이터이거나, 마지막 선택된 날짜로부터 7일 이상 경과한 경우
                            // 마지막 데이터 포인트는 항상 포함
                            boolean isLastItem = (i == sortedList.size() - 1);
                            if (lastSelectedDate == null || 
                                currentDate.isAfter(lastSelectedDate.plusDays(6)) || 
                                isLastItem) { // 7일 이상 차이 또는 마지막 데이터
                                result.add(mapToStockChartDto(stockCode, periodType, kisData));
                                lastSelectedDate = currentDate;
                            }
                        }
                        
                        return result;
                    })
                    .doOnError(e -> log.error("주식 차트 데이터 조회 중 오류 발생: {}", stockCode, e))
                    .onErrorResume(e -> Mono.error(new RuntimeException("주식 차트 데이터 조회 실패: " + stockCode, e)));
        }
        
        // 5년 - 1달 간격 (월봉)
        if ("5year".equals(normalizedType)) {
            LocalDate endDateLocal = LocalDate.now();
            LocalDate startDateLocal = endDateLocal.minusYears(5);
            return getChartDataFromKis(stockCode, "M", startDateLocal, endDateLocal) // 월봉
                    .map(chartDataList -> {
                        return chartDataList.stream()
                                .map(kisData -> mapToStockChartDto(stockCode, periodType, kisData))
                                .toList();
                    })
                    .doOnError(e -> log.error("주식 차트 데이터 조회 중 오류 발생: {}", stockCode, e))
                    .onErrorResume(e -> Mono.error(new RuntimeException("주식 차트 데이터 조회 실패: " + stockCode, e)));
        }

        throw new IllegalArgumentException("Invalid period type: " + periodType + ". Supported: 1day, 1week, 3month, 1year, 5year");
    }

    /**
     * KIS API에서 차트 데이터 조회
     */
    private Mono<List<KisChartDataDto>> getChartDataFromKis(
            String stockCode,
            String periodCode,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String accessToken = kisTokenManager.getAccessToken();
        String startDateStr = startDate.format(DATE_FORMATTER);
        String endDateStr = endDate.format(DATE_FORMATTER);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")  // J: 주식
                        .queryParam("FID_INPUT_ISCD", stockCode)     // 종목코드
                        .queryParam("FID_INPUT_DATE_1", startDateStr) // 시작일자
                        .queryParam("FID_INPUT_DATE_2", endDateStr)  // 종료일자
                        .queryParam("FID_PERIOD_DIV_CODE", periodCode) // 기간구분코드 (D/W/M/Y)
                        .queryParam("FID_ORG_ADJ_PRC", "0")          // 0: 수정주가
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", "FHKST03010100")  // 국내주식기간별시세(일/주/월/년) TR ID
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(String.class)
                .map(rawResponse -> {
                    try {
                        return objectMapper.readValue(rawResponse, KisChartResponseDto.class);
                    } catch (Exception e) {
                        log.error("KIS API 응답 파싱 실패. 원본 응답: {}", rawResponse, e);
                        throw new RuntimeException("KIS API 응답 파싱 실패", e);
                    }
                })
                .map(response -> {
                    if (!"0".equals(response.rtCd())) {
                        log.error("KIS API 오류 - 응답 코드: {}, 메시지: {}, msgCd: {}", 
                                response.rtCd(), response.msg1(), response.msgCd());
                        throw new RuntimeException("KIS API 오류: " + response.msg1() + " (코드: " + response.rtCd() + ")");
                    }

                    if (response.output2() == null) {
                        log.warn("KIS API 응답은 성공이지만 output2가 null입니다. 전체 응답: {}", response);
                        // 빈 리스트 반환 (데이터가 없는 경우)
                        return new ArrayList<KisChartDataDto>();
                    }

                    // output2를 KisChartDataDto 리스트로 변환
                    return parseOutputData(response.output2());
                })
                .doOnError(e -> log.error("KIS API 차트 데이터 조회 중 오류 발생", e));
    }

    /**
     * KIS API에서 분봉 데이터 조회 (당일 분봉 - 여러 번 호출하여 합치기)
     * 장 시작 시간(09:00)부터 현재 시간까지 30분 단위로 나눠서 비동기 호출 후 합치기
     * @param stockCode 종목코드
     * @param minuteInterval 분봉 간격 (1분 또는 10분)
     */
    private Mono<List<KisMinuteChartDataDto>> getMinuteChartDataFromKisMultiple(String stockCode, int minuteInterval) {
        LocalTime now = LocalTime.now();
        LocalTime marketStart = LocalTime.of(9, 0); // 장 시작 시간
        LocalTime marketEnd = LocalTime.of(15, 30); // 장 종료 시간
        
        // 현재 시간이 장 시작 전이면 빈 리스트 반환
        if (now.isBefore(marketStart)) {
            return Mono.just(new ArrayList<>());
        }
        
        // 조회할 시간 범위 계산 (30분 단위)
        LocalTime endTime = now.isAfter(marketEnd) ? marketEnd : now;
        List<String> timeRanges = calculateTimeRanges(marketStart, endTime);
        
        // 각 시간 범위마다 비동기로 호출
        List<Mono<List<KisMinuteChartDataDto>>> monos = timeRanges.stream()
                .map(startTime -> getMinuteChartDataFromKis(stockCode, startTime, minuteInterval))
                .toList();
        
        // 모든 Mono를 병렬로 실행하고 합치기
        return Flux.merge(monos)
                .collectList()
                .map(listOfLists -> {
                    // 모든 리스트를 하나로 합치기
                    List<KisMinuteChartDataDto> allData = new ArrayList<>();
                    for (List<KisMinuteChartDataDto> list : listOfLists) {
                        allData.addAll(list);
                    }
                    
                    // 중복 제거 및 시간 순서로 정렬
                    return deduplicateAndSort(allData);
                });
    }

    /**
     * 시간 범위를 30분 단위로 나누기
     */
    private List<String> calculateTimeRanges(LocalTime start, LocalTime end) {
        List<String> ranges = new ArrayList<>();
        LocalTime current = start;
        
        while (current.isBefore(end) || current.equals(end)) {
            // HHMMSS 형식으로 변환
            String timeStr = String.format("%02d%02d%02d", current.getHour(), current.getMinute(), 0);
            ranges.add(timeStr);
            
            // 30분 추가
            current = current.plusMinutes(30);
            
            // 종료 시간을 넘으면 중단
            if (current.isAfter(end)) {
                break;
            }
        }
        
        return ranges;
    }

    /**
     * 중복 제거 및 시간 순서로 정렬
     */
    private List<KisMinuteChartDataDto> deduplicateAndSort(List<KisMinuteChartDataDto> data) {
        // 날짜+시간을 키로 사용하여 중복 제거 (LinkedHashSet으로 순서 유지)
        Set<String> seen = new LinkedHashSet<>();
        List<KisMinuteChartDataDto> unique = new ArrayList<>();
        
        for (KisMinuteChartDataDto item : data) {
            String key = item.date() + "_" + item.time();
            if (!seen.contains(key)) {
                seen.add(key);
                unique.add(item);
            }
        }
        
        // 날짜+시간 순서로 정렬
        return unique.stream()
                .sorted(Comparator
                        .comparing(KisMinuteChartDataDto::date)
                        .thenComparing(KisMinuteChartDataDto::time))
                .collect(Collectors.toList());
    }

    /**
     * 1주일 분봉 데이터 조회 (1주일 전부터 현재까지)
     * 분봉 API는 당일만 조회 가능하므로, 당일 분봉을 조회하고 10분 간격으로 필터링
     */
    private Mono<List<KisMinuteChartDataDto>> getMinuteChartDataForWeek(String stockCode, int minuteInterval) {
        // 분봉 API는 당일만 조회 가능하므로, 당일 분봉을 1분 간격으로 조회
        // 10분 간격 필터링은 상위에서 처리
        return getMinuteChartDataFromKisMultiple(stockCode, 1);
    }

    /**
     * KIS API에서 분봉 데이터 조회 (특정 시간부터 30분)
     * @param stockCode 종목코드
     * @param startTime 시작 시간 (HHMMSS 형식)
     * @param minuteInterval 분봉 간격 (1분 또는 10분) - KIS API 파라미터에 추가 필요
     */
    private Mono<List<KisMinuteChartDataDto>> getMinuteChartDataFromKis(String stockCode, String startTime) {
        return getMinuteChartDataFromKis(stockCode, startTime, 1); // 기본값 1분
    }

    /**
     * KIS API에서 분봉 데이터 조회 (특정 시간부터, 분봉 간격 지정)
     */
    private Mono<List<KisMinuteChartDataDto>> getMinuteChartDataFromKis(String stockCode, String startTime, int minuteInterval) {
        String accessToken = kisTokenManager.getAccessToken();
        // FID_INPUT_HOUR_1: 조회 시작 시간 (HHMMSS 형식, 빈 값이면 전체)
        // FID_PW_DATA_INCU_YN: 과거 데이터 포함 여부 (Y/N)
        // FID_ETC_CLS_CODE: 기타 구분 코드 (필수)
        // FID_PERIOD_DIV_CODE: 분봉 간격 (1분/5분/10분/30분 등) - KIS API 문서 확인 필요

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")  // J: 주식
                        .queryParam("FID_INPUT_ISCD", stockCode)     // 종목코드
                        .queryParam("FID_INPUT_HOUR_1", startTime)  // 입력 시간1 (HHMMSS 형식)
                        .queryParam("FID_PW_DATA_INCU_YN", "N")     // 과거 데이터 포함 여부
                        .queryParam("FID_ETC_CLS_CODE", "0")         // 기타 구분 코드 (0: 기본값)
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", "FHKST03010200")  // 주식당일분봉조회 TR ID
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(String.class)
                .map(rawResponse -> {
                    try {
                        return objectMapper.readValue(rawResponse, KisChartResponseDto.class);
                    } catch (Exception e) {
                        log.error("KIS API 분봉 응답 파싱 실패. 원본 응답: {}", rawResponse, e);
                        throw new RuntimeException("KIS API 응답 파싱 실패", e);
                    }
                })
                .map(response -> {
                    if (!"0".equals(response.rtCd())) {
                        log.error("KIS API 분봉 오류 - 응답 코드: {}, 메시지: {}, msgCd: {}", 
                                response.rtCd(), response.msg1(), response.msgCd());
                        throw new RuntimeException("KIS API 오류: " + response.msg1() + " (코드: " + response.rtCd() + ")");
                    }

                    if (response.output2() == null) {
                        log.warn("KIS API 분봉 응답은 성공이지만 output2가 null입니다. 전체 응답: {}", response);
                        return new ArrayList<KisMinuteChartDataDto>();
                    }

                    // output2를 KisMinuteChartDataDto 리스트로 변환
                    return parseMinuteOutputData(response.output2());
                })
                .doOnError(e -> log.error("KIS API 분봉 데이터 조회 중 오류 발생", e));
    }

    /**
     * 기간 타입을 KIS API 코드로 매핑
     */
    private String mapPeriodTypeToKisCode(String periodType) {
        return switch (periodType.toLowerCase()) {
            case "day" -> "D";   // 일봉
            case "week" -> "W";  // 주봉
            case "month" -> "M"; // 월봉
            case "year" -> "Y";  // 년봉
            default -> throw new IllegalArgumentException("Invalid period type: " + periodType);
        };
    }

    /**
     * 시작일자 계산 (기본값 사용)
     */
    private LocalDate calculateStartDate(String periodType, LocalDate endDate) {
        int defaultCount = getDefaultCount(periodType);

        // 기간 타입에 따라 계산
        return switch (periodType.toLowerCase()) {
            case "day" -> endDate.minusDays(defaultCount);
            case "week" -> endDate.minusWeeks(defaultCount);
            case "month" -> endDate.minusMonths(defaultCount);
            case "year" -> endDate.minusYears(defaultCount);
            default -> throw new IllegalArgumentException("Invalid period type: " + periodType);
        };
    }

    /**
     * 기간 타입별 기본 조회 개수
     */
    private int getDefaultCount(String periodType) {
        return switch (periodType.toLowerCase()) {
            case "day" -> 30;   // 최근 30일
            case "week" -> 12;  // 최근 12주
            case "month" -> 12; // 최근 12개월
            case "year" -> 5;   // 최근 5년
            default -> 30;
        };
    }

    /**
     * 날짜 문자열 파싱
     */
    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date format: " + dateStr + " (expected: yyyyMMdd)", e);
        }
    }

    /**
     * 시간 문자열 파싱 (HHMMSS 형식)
     */
    private LocalTime parseTime(String timeStr) {
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

    /**
     * KIS API 분봉 응답을 StockChartDto로 변환
     */
    private StockChartDto mapMinuteToStockChartDto(String stockCode, String periodType, KisMinuteChartDataDto kisData) {
        // 분봉은 전일대비 정보가 없으므로 0으로 설정
        // stck_prpr (현재가)를 종가로 사용
        return new StockChartDto(
                stockCode,
                periodType,
                parseDate(kisData.date()),
                parseTime(kisData.time()), // 시간 정보 포함 (HHMMSS 형식)
                parseIntValue(kisData.openPrice()),
                parseIntValue(kisData.highPrice()),
                parseIntValue(kisData.lowPrice()),
                parseIntValue(kisData.currentPrice()), // stck_prpr를 종가로 사용
                parseLongValue(kisData.volume()), // cntg_vol (체결 거래량)
                parseLongValue(kisData.amount()),
                0, // 전일대비 (분봉에는 없음)
                "0" // 전일대비율 (분봉에는 없음)
        );
    }

    /**
     * KIS API 응답을 StockChartDto로 변환
     */
    private StockChartDto mapToStockChartDto(String stockCode, String periodType, KisChartDataDto kisData) {
        // changeRate가 없으면 changeAmount와 closePrice로 계산
        String changeRate = kisData.changeRate();
        if (changeRate == null || changeRate.trim().isEmpty()) {
            int closePrice = parseIntValue(kisData.closePrice());
            int changeAmount = parseIntValue(kisData.changeAmount());
            if (closePrice != 0 && changeAmount != 0) {
                double rate = (changeAmount / (double)(closePrice - changeAmount)) * 100;
                changeRate = String.format("%.2f", rate);
            } else {
                changeRate = "0";
            }
        }
        
        return new StockChartDto(
                stockCode,
                periodType,
                parseDate(kisData.date()),
                null, // 일/주/월/년봉은 시간 정보 없음
                parseIntValue(kisData.openPrice()),
                parseIntValue(kisData.highPrice()),
                parseIntValue(kisData.lowPrice()),
                parseIntValue(kisData.closePrice()),
                parseLongValue(kisData.volume()),
                parseLongValue(kisData.amount()),
                parseIntValue(kisData.changeAmount()),
                changeRate
        );
    }

    /**
     * 분봉 output2 데이터를 KisMinuteChartDataDto 리스트로 변환
     */
    private List<KisMinuteChartDataDto> parseMinuteOutputData(Object output) {
        try {
            List<KisMinuteChartDataDto> result = new ArrayList<>();

            if (output instanceof List) {
                // output2가 List인 경우
                List<?> dataList = (List<?>) output;
                
                for (Object item : dataList) {
                    if (item instanceof Map) {
                        KisMinuteChartDataDto dto = objectMapper.convertValue(item, KisMinuteChartDataDto.class);
                        result.add(dto);
                    } else {
                        log.warn("분봉 List 내부 항목이 Map이 아닙니다: {}", item.getClass());
                    }
                }
            } else {
                log.warn("예상하지 못한 분봉 output 구조: {} - {}", output.getClass(), output);
            }

            return result;

        } catch (Exception e) {
            log.error("분봉 output 데이터 파싱 중 오류 발생. output: {}", output, e);
            throw new RuntimeException("KIS API 분봉 응답 데이터 파싱 실패", e);
        }
    }

    /**
     * output 데이터를 KisChartDataDto 리스트로 변환
     */
    @SuppressWarnings("unchecked")
    private List<KisChartDataDto> parseOutputData(Object output) {
        try {
            List<KisChartDataDto> result = new ArrayList<>();

            if (output instanceof List) {
                // output이 List인 경우
                List<?> dataList = (List<?>) output;
                
                for (Object item : dataList) {
                    if (item instanceof Map) {
                        KisChartDataDto dto = objectMapper.convertValue(item, KisChartDataDto.class);
                        result.add(dto);
                    } else {
                        log.warn("List 내부 항목이 Map이 아닙니다: {}", item.getClass());
                    }
                }
            } else if (output instanceof Map) {
                // output이 Map인 경우
                Map<String, Object> outputMap = (Map<String, Object>) output;
                
                // "output" 키가 있는지 확인 (중첩 구조)
                if (outputMap.containsKey("output")) {
                    Object nestedOutput = outputMap.get("output");
                    if (nestedOutput instanceof List) {
                        List<?> dataList = (List<?>) nestedOutput;
                        for (Object item : dataList) {
                            if (item instanceof Map) {
                                KisChartDataDto dto = objectMapper.convertValue(item, KisChartDataDto.class);
                                result.add(dto);
                            }
                        }
                    } else if (nestedOutput instanceof Map) {
                        KisChartDataDto dto = objectMapper.convertValue(nestedOutput, KisChartDataDto.class);
                        result.add(dto);
                    }
                } else {
                    // 직접 Map인 경우
                    KisChartDataDto dto = objectMapper.convertValue(output, KisChartDataDto.class);
                    result.add(dto);
                }
            } else {
                log.warn("예상하지 못한 output 구조: {} - {}", output.getClass(), output);
            }

            return result;

        } catch (Exception e) {
            log.error("output 데이터 파싱 중 오류 발생. output: {}", output, e);
            throw new RuntimeException("KIS API 응답 데이터 파싱 실패", e);
        }
    }

    /**
     * String을 Integer로 안전하게 변환
     */
    private Integer parseIntValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Integer 파싱 실패: {}", value);
            return 0;
        }
    }

    /**
     * String을 Long으로 안전하게 변환
     */
    private Long parseLongValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Long 파싱 실패: {}", value);
            return 0L;
        }
    }
}

