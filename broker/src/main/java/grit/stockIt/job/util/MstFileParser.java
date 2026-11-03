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
    private static final int KOR_ISNM_LEN = 40;    // 한글명
    private static final int STCK_LSTN_DATE_LEN = 8; // 상장일자

    // 필드 시작 인덱스 계산 (0부터 시작)
    private static final int SHRN_ISCD_START = 0;
    private static final int STND_ISCD_START = SHRN_ISCD_START + SHRN_ISCD_LEN;
    private static final int KOR_ISNM_START = STND_ISCD_START + STND_ISCD_LEN;

    // ST 위치 이후 상대 위치로 상장일자 계산
    // KOSDAQ: ST 이후 100자리부터 상장일자 (8자리)
    // KOSPI: ST 이후 105자리부터 상장일자 (8자리)
    private static final int KSQ_STCK_LSTN_DATE_OFFSET_FROM_ST = 100; // KOSDAQ
    private static final int KSP_STCK_LSTN_DATE_OFFSET_FROM_ST = 105; // KOSPI

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE; // YYYYMMDD

    //MST 파일 내용을 파싱하여 MstRecord 리스트로 반환
    public List<MstRecord> parseMstContent(String mstContent, String marketType) {
        List<MstRecord> parsedList = new ArrayList<>();
        List<String> lines = Arrays.asList(mstContent.split("\\r?\\n"));

        for (String line : lines) {
            try {
                // 최소 길이 체크 (가장 긴 필드의 끝 위치 이상이어야 함)
                if (line.length() < 200) continue; // 최소 길이보다 짧으면 건너뛰기

                // 한글명 필드(21-61자리)에서 실제 이름이 끝나는 위치 찾기
                // 이름은 공백 없이 연속된 문자로, 공백이 나타나면 그 전까지가 이름
                int nameFieldEnd = KOR_ISNM_START + KOR_ISNM_LEN;
                int nameEndInField = -1;
                
                // 한글명 필드 내에서 첫 번째 연속된 공백 그룹의 시작 위치 찾기
                boolean foundNonSpace = false;
                for (int i = KOR_ISNM_START; i < nameFieldEnd && i < line.length(); i++) {
                    if (line.charAt(i) != ' ') {
                        foundNonSpace = true;
                    } else if (foundNonSpace && line.charAt(i) == ' ') {
                        // 이미 비공백 문자를 만났고, 이제 공백을 만났으면
                        // 이전 위치가 이름의 끝
                        nameEndInField = i;
                        break;
                    }
                }
                
                // 이름 끝을 찾지 못했으면 한글명 필드 끝까지가 이름
                int nameEndPos = (nameEndInField >= 0) ? nameEndInField : nameFieldEnd;
                
                // 주식 이름 추출 (21자리부터 이름 끝까지)
                String stockName = line.substring(KOR_ISNM_START, nameEndPos).trim();
                
                // 이름 끝부터 시작 (한글명 필드 내 나머지 공백 포함)
                String remainingData = line.substring(nameEndPos);
                // 공백을 건너뛰고 첫 번째 비공백 문자부터 검색
                int searchStart = 0;
                while (searchStart < remainingData.length() && remainingData.charAt(searchStart) == ' ') {
                    searchStart++;
                }
                
                String dataAfterSpaces = remainingData.substring(searchStart);
                
                // 공백 뒤 첫 2자리가 증권그룹구분코드 (ST, FS, EN, EF, BC, EW 등)
                if (dataAfterSpaces.length() < 2) {
                    continue; // 데이터 부족
                }
                
                String securityGroupCode = dataAfterSpaces.substring(0, 2);
                String securityGroup = "";
                String industryCode = "";
                
                // ST나 FS만 처리, 다른 증권그룹구분코드는 패스
                if ("ST".equals(securityGroupCode)) {
                    securityGroup = "ST";
                    // ST 다음에 오는 데이터: 시가총액규모(1자리) + 산업코드(4자리)
                    if (dataAfterSpaces.length() >= 7) {
                        industryCode = dataAfterSpaces.substring(3, 7).trim(); // 시가총액규모 건너뛰고 산업코드 4자리
                    }
                } else if ("FS".equals(securityGroupCode)) {
                    securityGroup = "FS";
                    // FS 다음에 오는 데이터: 시가총액규모(1자리) + 산업코드(4자리)
                    if (dataAfterSpaces.length() >= 7) {
                        industryCode = dataAfterSpaces.substring(3, 7).trim(); // 시가총액규모 건너뛰고 산업코드 4자리
                    }
                }
                // EN, EF, BC, EW 등은 securityGroup이 ""로 남아서 필터링됨

                // 주권(ST), 외국주권(FS)만 처리
                if ("ST".equals(securityGroup) || "FS".equals(securityGroup)) {
                    String stockCode = safeSubstring(line, SHRN_ISCD_START, SHRN_ISCD_LEN);

                    // 상장일자 파싱: ST 위치를 기준으로 상대 위치 계산
                    // ST 위치 = nameEndPos + searchStart
                    int stPosition = nameEndPos + searchStart;
                    int listingDateOffset = "KOSPI".equals(marketType) 
                            ? KSP_STCK_LSTN_DATE_OFFSET_FROM_ST 
                            : KSQ_STCK_LSTN_DATE_OFFSET_FROM_ST;
                    int listingDateStart = stPosition + listingDateOffset;
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