package grit.stockIt.domain.llm.service;

import grit.stockIt.domain.llm.repository.CompanyDescriptionCacheRepository;
import grit.stockIt.global.config.GeminiApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gemini LLM 서비스
 * 기업 설명 생성을 위한 LLM API 호출 및 캐싱 관리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiLlmService {

    private static final String PROMPT_TEMPLATE = """
        당신은 한국 주식시장의 상장 기업을 전문적으로 분석하는 금융 AI 전문가입니다.
        사용자가 요청한 기업명인 '%s'에 대해 설명해 주세요.
        답변은 핵심 사업 분야, 주요 제품, 그리고 산업에서의 위치를 포함하여 **두 문장 이내**로 간결하게 작성해야 합니다.
        
        예시 답변 형식:
        "삼성전자(Samsung Electronics)는 대한민국을 대표하는 세계적인 종합 전자 기업입니다. 메모리 반도체, 스마트폰, TV, 가전제품 등 광범위한 분야에서 글로벌 리더십을 가지고 있습니다."
        """;

    private static final String FALLBACK_TEXT_TEMPLATE = "%s은(는) 한국 주식시장에 상장된 기업입니다. (현재 AI 분석량이 많아 상세 정보를 불러오지 못했습니다.)";

    private final CompanyDescriptionCacheRepository cacheRepository;
    private final WebClient.Builder webClientBuilder;
    private final GeminiApiProperties geminiProperties;

    /**
     * 기업 설명 조회 (캐시 우선, 없으면 Gemini API 호출)
     */
    public Mono<CompanyDescriptionResult> getCompanyDescription(
            String companyName,
            boolean useCache
    ) {
        // 1. 캐시 확인
        if (useCache) {
            Optional<String> cached = cacheRepository.get(companyName);
            if (cached.isPresent()) {
                log.info("캐시에서 기업 설명 조회: {}", companyName);
                return Mono.just(new CompanyDescriptionResult(
                        cached.get(),
                        true
                ));
            }
        }

        // 2. 캐시 미스 → Gemini API 호출
        String prompt = String.format(PROMPT_TEMPLATE, companyName);
        return callGeminiApi(prompt, companyName)
                .flatMap(description -> {
                    // 3. 캐시 저장
                    if (useCache) {
                        cacheRepository.save(companyName, description);
                    }
                    return Mono.just(new CompanyDescriptionResult(description, false));
                })
                .onErrorResume(e -> {
                    log.error("Gemini API 호출 실패: {}", companyName, e);
                    // 4. 실패 시 안전한 기본값 반환
                    String fallback = String.format(FALLBACK_TEXT_TEMPLATE, companyName);
                    return Mono.just(new CompanyDescriptionResult(fallback, false));
                });
    }

    /**
     * Gemini API 호출
     */
    private Mono<String> callGeminiApi(String prompt, String companyName) {
        String url = String.format(
                "%s/%s:generateContent?key=%s",
                geminiProperties.baseUrl(),
                geminiProperties.modelName(),
                geminiProperties.apiKey()
        );

        // 요청 Body 생성
        Map<String, Object> content = Map.of(
                "parts", List.of(Map.of("text", prompt))
        );
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(content)
        );

        log.info("Gemini API 호출: companyName={}, model={}", companyName, geminiProperties.modelName());

        return webClientBuilder
                .build()
                .post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseResponse)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                        .filter(e -> {
                            String errorMsg = e.getMessage();
                            return errorMsg != null && (
                                    errorMsg.contains("429") ||
                                    errorMsg.contains("503") ||
                                    errorMsg.contains("RESOURCE_EXHAUSTED")
                            );
                        })
                        .doBeforeRetry(retry ->
                                log.warn("⚠️ API 과부하/제한. 재시도 중... ({}/{})",
                                        retry.totalRetries() + 1, 3)
                        )
                )
                .doOnSuccess(response ->
                        log.info("Gemini API 호출 성공: companyName={}", companyName)
                )
                .doOnError(error ->
                        log.error("Gemini API 호출 실패: companyName={}", companyName, error)
                );
    }

    /**
     * Gemini API 응답 파싱
     */
    @SuppressWarnings("unchecked")
    private String parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> candidates =
                (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new RuntimeException("Empty response from Gemini");
        }

        Map<String, Object> candidate = candidates.get(0);
        Map<String, Object> content =
                (Map<String, Object>) candidate.get("content");
        if (content == null) {
            throw new RuntimeException("Empty content in response");
        }

        List<Map<String, String>> parts =
                (List<Map<String, String>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new RuntimeException("Empty parts in response");
        }

        String text = parts.get(0).get("text");
        if (text == null || text.trim().isEmpty()) {
            throw new RuntimeException("Empty text in response");
        }

        return text.trim();
    }

    /**
     * 기업 설명 결과를 담을 내부 Record
     */
    public record CompanyDescriptionResult(
            String description,
            boolean cached
    ) {}
}

