package grit.stockIt.domain.title.controller;

import grit.stockIt.contract.ExtraQueryApi;
import grit.stockIt.contract.TitleView;
import grit.stockIt.domain.title.service.LocalExtraQueryClient;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 증권사의 동기 조회(ExtraQueryClient)에 HTTP 로 답한다. 내부망 전용.
@Hidden
@RestController
@RequestMapping(ExtraQueryApi.BASE)
@ConditionalOnProperty(name = "stockit.internal-api.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InternalExtraQueryController {

    private final LocalExtraQueryClient extraQueryClient;

    @GetMapping(ExtraQueryApi.TITLE)
    public ResponseEntity<TitleView> title(@PathVariable Long titleId) {
        return extraQueryClient.title(titleId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(ExtraQueryApi.TITLE_OWNED)
    public ExtraQueryApi.Owned owned(@PathVariable Long memberId, @PathVariable Long titleId) {
        return new ExtraQueryApi.Owned(extraQueryClient.ownsTitle(memberId, titleId));
    }

    @GetMapping(ExtraQueryApi.MEMBER_TITLES)
    public List<TitleView> titlesOf(@PathVariable Long memberId) {
        return extraQueryClient.titlesOf(memberId);
    }
}
