package grit.stockIt.domain.stock.analysis.service;

import grit.stockIt.domain.stock.analysis.dto.KisDividendOutput;
import grit.stockIt.domain.stock.analysis.dto.KisDividendResponseDto;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

// KIS API 배당정보 조회 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class KisDividendService {

    private static final String TR_ID = "HHKDB669102C0";
    private static final String CTS_BLANK = "               ";  // 공백 17자

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;

    // KIS API에서 배당정보 조회
    public Mono<KisDividendOutput> getDividendInfo(String stockCode) {
        String accessToken = kisTokenManager.getAccessToken();

        // 최근 1년 데이터 조회
        LocalDate today = LocalDate.now();
        LocalDate oneYearAgo = today.minusYears(1);
        String fromDate = oneYearAgo.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String toDate = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 종목코드를 9자리로 맞춤 (공백으로 채움)
        String paddedStockCode = String.format("%-9s", stockCode);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/ksdinfo/dividend")
                        .queryParam("CTS", CTS_BLANK)  // 공백 17자
                        .queryParam("GB1", "0")         // 0: 배당전체
                        .queryParam("F_DT", fromDate)   // 조회시작일
                        .queryParam("T_DT", toDate)     // 조회종료일
                        .queryParam("SHT_CD", paddedStockCode)  // 종목코드 (9자)
                        .queryParam("HIGH_GB", " ")     // 공백
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", TR_ID)
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisDividendResponseDto.class)
                .map(response -> {
                    log.info("KIS 배당정보 API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());

                    if (!"0".equals(response.rtCd())) {
                        throw new RuntimeException("KIS API 오류: " + response.msg1());
                    }

                    if (response.output1() == null || response.output1().isEmpty()) {
                        // 배당 정보가 없을 수 있음 (배당을 하지 않는 종목)
                        log.warn("배당 정보가 없습니다: {}", stockCode);
                        return null;
                    }

                    // 가장 최신 배당 정보 반환 (첫 번째)
                    return response.output1().get(0);
                })
                .doOnError(e -> log.error("KIS 배당정보 API 조회 중 오류 발생: stockCode={}", stockCode, e));
    }
}

