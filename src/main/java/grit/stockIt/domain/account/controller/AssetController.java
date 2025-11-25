package grit.stockIt.domain.account.controller;

import grit.stockIt.domain.account.dto.AssetResponse;
import grit.stockIt.domain.account.dto.MyStockResponse;
import grit.stockIt.domain.account.service.AssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/accounts")
@Tag(name = "accounts", description = "계좌 관련 API")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @Operation(summary = "사용자 자산 조회", description = "계좌의 보유종목 정보를 조회합니다.")
    @GetMapping("/{accountId}/assets")
    public AssetResponse getAssets(@PathVariable Long accountId) {
        return assetService.getAssets(accountId);
    }

    @Operation(summary = "내 주식 조회", description = "특정 종목의 보유 정보 및 주문 내역을 조회합니다.")
    @GetMapping("/{accountId}/stocks/{stockCode}")
    public MyStockResponse getMyStock(
            @PathVariable Long accountId,
            @PathVariable String stockCode,
            @Parameter(description = "취소된 주문 포함 여부", example = "true")
            @RequestParam(defaultValue = "true") boolean includeCancelled
    ) {
        return assetService.getMyStock(accountId, stockCode, includeCancelled);
    }
}

