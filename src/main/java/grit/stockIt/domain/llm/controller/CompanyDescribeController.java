package grit.stockIt.domain.llm.controller;

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
}

