package grit.stockIt.domain.stock.analysis.service;

import grit.stockIt.domain.stock.analysis.dto.KisFinancialRatioOutput;
import grit.stockIt.domain.stock.analysis.dto.KisFinancialRatioResponseDto;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.config.KisApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

// KIS API 재무비율 조회 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class KisFinancialRatioService {

    private static final String TR_ID = "FHKST66430300";

    private final WebClient webClient;
    private final KisTokenManager kisTokenManager;
    private final KisApiProperties kisApiProperties;

    // KIS API에서 재무비율 조회
    public Mono<KisFinancialRatioOutput> getFinancialRatio(String stockCode) {
        String accessToken = kisTokenManager.getAccessToken();

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/uapi/domestic-stock/v1/finance/financial-ratio")
                        .queryParam("FID_DIV_CLS_CODE", "0")  // 0: 년, 1: 분기
                        .queryParam("fid_cond_mrkt_div_code", "J")  // J: 주식
                        .queryParam("fid_input_iscd", stockCode)
                        .build())
                .header("content-type", "application/json; charset=utf-8")
                .header("authorization", "Bearer " + accessToken)
                .header("appkey", kisApiProperties.appkey())
                .header("appsecret", kisApiProperties.appsecret())
                .header("tr_id", TR_ID)
                .header("custtype", "P")
                .retrieve()
                .bodyToMono(KisFinancialRatioResponseDto.class)
                .map(response -> {
                    log.info("KIS 재무비율 API 응답 코드: {}, 메시지: {}", response.rtCd(), response.msg1());

                    if (!"0".equals(response.rtCd())) {
                        throw new RuntimeException("KIS API 오류: " + response.msg1());
                    }

                    if (response.output() == null || response.output().isEmpty()) {
                        throw new RuntimeException("KIS API 응답 데이터가 없습니다.");
                    }

                    // 가장 최신 데이터 반환 (첫 번째)
                    return response.output().get(0);
                })
                .doOnError(e -> log.error("KIS 재무비율 API 조회 중 오류 발생: stockCode={}", stockCode, e));
    }
}

