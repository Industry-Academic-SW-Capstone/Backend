package grit.stockIt.job.util;

import grit.stockIt.job.dto.MstRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class MstFileParser {
    private static final int SHRN_ISCD_LEN = 9;    // 단축코드
    private static final int STND_ISCD_LEN = 12;   // 표준코드
    private static final int KOR_ISNM_LEN = 40;    // 한글명 (주의: 가장 불확실! 실제 길이에 맞게 조정)
    private static final int STCK_LSTN_DATE_LEN = 8; // 상장일자

    // 필드 시작 인덱스 계산 (0부터 시작)
    private static final int SHRN_ISCD_START = 0;
    private static final int STND_ISCD_START = SHRN_ISCD_START + SHRN_ISCD_LEN;
    private static final int KOR_ISNM_START = STND_ISCD_START + STND_ISCD_LEN;

    // 상장일자 위치 계산
    private static final int KSP_STCK_LSTN_DATE_START = 143;
    private static final int KSQ_STCK_LSTN_DATE_START = 138;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE; // YYYYMMDD

    //MST 파일 내용을 파싱하여 MstRecord 리스트로 반환
    public List<MstRecord> parseMstContent(String mstContent, String marketType) {
        List<MstRecord> parsedList = new ArrayList<>();
        List<String> lines = Arrays.asList(mstContent.split("\\r?\\n"));

        for (String line : lines) {
            try {
                // 최소 길이 체크 (가장 긴 필드의 끝 위치 이상이어야 함)
                if (line.length() < 200) continue; // 최소 길이보다 짧으면 건너뛰기

                // 한글명을 동적으로 추출하고, 그 다음부터 증권그룹과 산업코드 추출
                String stockName = extractKoreanName(line, KOR_ISNM_START, KOR_ISNM_LEN);
                int koreanNameEnd = KOR_ISNM_START + stockName.length();
                
                // 한글명 다음부터 ST/FS 찾기
                String remainingData = line.substring(koreanNameEnd);
                int stIndex = remainingData.indexOf("ST");
                int fsIndex = remainingData.indexOf("FS");
                
                String securityGroup = "";
                String industryCode = "";
                
                if (stIndex >= 0) {
                    securityGroup = "ST";
                    // ST 다음에 오는 데이터: 시가총액규모(1자리) + 산업코드(4자리)
                    String afterST = remainingData.substring(stIndex + 2);
                    if (afterST.length() >= 5) {
                        industryCode = afterST.substring(1, 5); // 시가총액규모 건너뛰고 산업코드 4자리
                    }
                } else if (fsIndex >= 0) {
                    securityGroup = "FS";
                    // FS 다음에 오는 데이터: 시가총액규모(1자리) + 산업코드(4자리)
                    String afterFS = remainingData.substring(fsIndex + 2);
                    if (afterFS.length() >= 5) {
                        industryCode = afterFS.substring(1, 5); // 시가총액규모 건너뛰고 산업코드 4자리
                    }
                }

                // 주권(ST), 외국주권(FS)만 처리
                if ("ST".equals(securityGroup) || "FS".equals(securityGroup)) {
                    String stockCode = safeSubstring(line, SHRN_ISCD_START, SHRN_ISCD_LEN);

                    // 상장일자 파싱
                    int listingDateStart = "KOSPI".equals(marketType) ? KSP_STCK_LSTN_DATE_START : KSQ_STCK_LSTN_DATE_START;
                    String listingDateStr = safeSubstring(line, listingDateStart, STCK_LSTN_DATE_LEN);
                    LocalDate listingDate = parseLocalDate(listingDateStr);

                    if (!stockCode.isEmpty()) {
                        parsedList.add(new MstRecord(stockCode, stockName, securityGroup, industryCode, marketType, listingDate));
                    }
                }
            } catch (Exception e) {
                log.warn("라인 파싱 중 오류: '{}', {}", line.substring(0, Math.min(line.length(), 50)), e.getMessage());
            }
        }
        log.info("{} file parsed. Found {} stock records.", marketType, parsedList.size());
        return parsedList;
    }

    // 안전하게 substring 하고 trim 하는 헬퍼 메서드
    private String safeSubstring(String line, int start, int length) {
        int end = start + length;
        if (line == null || line.length() < end || start < 0) {
            return ""; // 길이가 부족하거나 인덱스가 유효하지 않으면 빈 문자열 반환
        }
        return line.substring(start, end).trim();
    }
    
    // 한글명 필드에서 증권그룹(ST/FS)을 추출하는 메서드
    private String extractSecurityGroup(String rawKoreanField) {
        if (rawKoreanField == null || rawKoreanField.isEmpty()) {
            return "";
        }
        
        // ST나 FS가 포함되어 있는지 확인
        if (rawKoreanField.contains("ST")) {
            return "ST";
        } else if (rawKoreanField.contains("FS")) {
            return "FS";
        }
        
        return ""; // ST나 FS가 없으면 빈 문자열 반환
    }
    
    // 한글명 필드에서 실제 한글명만 추출하는 특별한 메서드
    private String extractKoreanName(String line, int start, int length) {
        String rawField = safeSubstring(line, start, length);
        if (rawField.isEmpty()) {
            return "";
        }
        
        // 첫 번째 공백 전까지의 텍스트
        int firstSpaceIndex = rawField.indexOf(' ');
        if (firstSpaceIndex > 0) {
            String candidate = rawField.substring(0, firstSpaceIndex).trim();
            // 한글이 포함되어 있는지 확인
            if (containsKorean(candidate)) {
                return candidate;
            }
        }
        
        // 연속된 공백이 나오기 전까지의 텍스트
        String[] parts = rawField.split("\\s+");
        if (parts.length > 0 && containsKorean(parts[0])) {
            return parts[0].trim();
        }
        
        // 전체 필드에서 한글만 추출
        StringBuilder koreanOnly = new StringBuilder();
        for (char c : rawField.toCharArray()) {
            if (isKorean(c)) {
                koreanOnly.append(c);
            } else if (koreanOnly.length() > 0 && c == ' ') {
                // 한글이 시작된 후 공백이 나오면 중단
                break;
            }
        }
        
        if (koreanOnly.length() > 0) {
            return koreanOnly.toString();
        }
        
        // 위의 방법들이 모두 실패하면 원본 반환
        return rawField.trim();
    }
    
    // 문자열에 한글이 포함되어 있는지 확인
    private boolean containsKorean(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (char c : text.toCharArray()) {
            if (isKorean(c)) {
                return true;
            }
        }
        return false;
    }
    
    // 문자가 한글인지 확인
    private boolean isKorean(char c) {
        return (c >= 0xAC00 && c <= 0xD7AF) || // 한글 완성형
               (c >= 0x1100 && c <= 0x11FF) || // 한글 자모
               (c >= 0x3130 && c <= 0x318F);   // 한글 호환 자모
    }

    // 날짜 문자열을 LocalDate로 파싱하는 헬퍼 메서드
    private LocalDate parseLocalDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty() || dateStr.equals("00000000")) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            log.warn("날짜 파싱 오류: {}", dateStr);
            return null;
        }
    }
}