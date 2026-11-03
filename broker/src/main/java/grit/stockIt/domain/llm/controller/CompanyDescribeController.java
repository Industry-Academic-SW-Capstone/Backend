package grit.stockIt.domain.llm.controller;

import grit.stockIt.domain.llm.dto.CachePerformanceResponse;
import grit.stockIt.domain.llm.dto.CompanyDescribeRequest;
import grit.stockIt.domain.llm.dto.CompanyDescriptionResponse;
import grit.stockIt.domain.llm.service.GeminiLlmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * 기업 설명 생성 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/company")
@Tag(name = "company-describe", description = "기업 설명 API")
@RequiredArgsConstructor
public class CompanyDescribeController {

    private final GeminiLlmService geminiLlmService;

    @Operation(
            summary = "기업 설명 생성",
            description = "기업명을 받아 Gemini LLM을 활용하여 두 문장 요약을 생성합니다. Redis 캐시를 사용하여 API 호출을 최소화합니다 (TTL: 3시간)."
    )
    @PostMapping("/describe")
    @ResponseStatus(HttpStatus.OK)
    public Mono<CompanyDescriptionResponse> describeCompany(
            @Valid @RequestBody CompanyDescribeRequest request
    ) {
        log.info("기업 설명 요청: {}", request.companyName());

        return geminiLlmService.getCompanyDescription(
                        request.companyName(),
                        true  // useCache = true
                )
                .map(result -> new CompanyDescriptionResponse(
                        request.companyName(),
                        result.description(),
                        result.cached()
                ))
                .doOnSuccess(response ->
                        log.info("기업 설명 생성 완료: companyName={}, cached={}",
                                response.companyName(), response.cached())
                )
                .doOnError(error ->
                        log.error("기업 설명 생성 오류: companyName={}", request.companyName(), error)
                )
                .onErrorResume(e -> {
                    log.error("기업 설명 생성 실패: {}", request.companyName(), e);
                    return Mono.error(new RuntimeException(
                            "기업 설명 생성에 실패했습니다: " + e.getMessage(),
                            e
                    ));
                });
    }

    @Operation(
            summary = "캐시 성능 비교 테스트",
            description = "캐시 없이 조회한 경우와 캐시로 조회한 경우의 성능을 비교합니다. " +
                    "먼저 캐시를 삭제하고 API를 호출하여 시간을 측정한 후, " +
                    "같은 요청을 다시 호출하여 캐시로 조회한 시간을 측정합니다."
    )
    @PostMapping("/describe/cache-performance")
    @ResponseStatus(HttpStatus.OK)
    public Mono<CachePerformanceResponse> compareCachePerformance(
            @Valid @RequestBody CompanyDescribeRequest request
    ) {
        log.info("캐시 성능 비교 테스트 요청: {}", request.companyName());

        String companyName = request.companyName();

        // 1. 캐시 삭제
        return geminiLlmService.clearCache(companyName)
                .then(Mono.defer(() -> {
                    // 2. 캐시 없이 조회 (시간 측정) - 이때 캐시에 저장됨
                    Instant startWithoutCache = Instant.now();
                    return geminiLlmService.getCompanyDescription(companyName, true)
                            .map(result -> {
                                Instant endWithoutCache = Instant.now();
                                long durationWithoutCache = Duration.between(startWithoutCache, endWithoutCache).toMillis();
                                
                                return new CachePerformanceResponse.PerformanceResult(
                                        durationWithoutCache,
                                        result.description(),
                                        result.cached()
                                );
                            });
                }))
                .flatMap(withoutCacheResult -> {
                    // 3. 캐시로 조회 (시간 측정)
                    Instant startWithCache = Instant.now();
                    return geminiLlmService.getCompanyDescription(companyName, true)
                            .map(result -> {
                                Instant endWithCache = Instant.now();
                                long durationWithCache = Duration.between(startWithCache, endWithCache).toMillis();
                                
                                long timeSaved = withoutCacheResult.durationMs() - durationWithCache;
                                String timeSavedDescription = String.format(
                                        "원래라면 똑같은걸 검색하면 이렇게 %d초 걸리던걸 캐시 걸려서 %d초 걸렸다. %d초를 단축할 수 있었다.",
                                        withoutCacheResult.durationMs() / 1000,
                                        durationWithCache / 1000,
                                        timeSaved / 1000
                                );
                                
                                return new CachePerformanceResponse(
                                        companyName,
                                        withoutCacheResult,
                                        new CachePerformanceResponse.PerformanceResult(
                                                durationWithCache,
                                                result.description(),
                                                result.cached()
                                        ),
                                        timeSaved,
                                        timeSavedDescription
                                );
                            });
                })
                .doOnSuccess(response ->
                        log.info("캐시 성능 비교 테스트 완료: companyName={}, 시간 절약={}ms",
                                response.companyName(), response.timeSaved())
                )
                .doOnError(error ->
                        log.error("캐시 성능 비교 테스트 오류: companyName={}", companyName, error)
                );
    }
}

