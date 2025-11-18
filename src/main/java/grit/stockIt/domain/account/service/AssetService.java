package grit.stockIt.domain.account.service;

import grit.stockIt.domain.account.dto.AssetResponse;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

// 사용자 자산 조회 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final StockDetailService stockDetailService;

    private static final Duration KIS_API_TIMEOUT = Duration.ofSeconds(5);

    // 사용자 자산 조회
    @Transactional(readOnly = true)
    public AssetResponse getAssets(Long accountId) {
        // Account 조회 및 권한 확인
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        ensureAccountOwner(account);

        // 보유종목 목록 조회
        List<AccountStock> accountStocks = accountStockRepository.findByAccountIdWithStock(accountId);

        if (accountStocks.isEmpty()) {
            log.info("보유종목이 없습니다: accountId={}", accountId);
            return new AssetResponse(
                    account.getCash(),           // totalAssets = 현금만
                    account.getCash(),           // cash
                    BigDecimal.ZERO,             // stockValue = 0
                    List.of()                    // holdings
            );
        }

        // 각 종목의 현재가 조회 (병렬 처리)
        List<Mono<AssetResponse.HoldingItem>> holdingMonos = accountStocks.stream()
                .map(accountStock -> {
                    String stockCode = accountStock.getStock().getCode();
                    return stockDetailService.getCurrentPrice(stockCode)
                            .timeout(KIS_API_TIMEOUT)
                            .onErrorResume(e -> {
                                log.warn("KIS API 호출 실패: stockCode={}, error={}", stockCode, e.getMessage());
                                // 실패 시 평단가 사용
                                return Mono.just(accountStock.getAveragePrice());
                            })
                            .map(currentPrice -> convertToHoldingItem(accountStock, currentPrice));
                })
                .toList();

        // 병렬로 모든 Mono를 실행하고 결과 수집
        List<AssetResponse.HoldingItem> holdings = Flux.merge(holdingMonos)
                .collectList()
                .block();

        // 총 자산 계산
        BigDecimal totalStockValue = holdings.stream()
                .map(AssetResponse.HoldingItem::totalValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAssets = account.getCash().add(totalStockValue);

        log.info("자산 조회 완료: accountId={}, totalAssets={}, cash={}, stockValue={}, holdingsCount={}", 
                accountId, totalAssets, account.getCash(), totalStockValue, holdings.size());
        return new AssetResponse(totalAssets, account.getCash(), totalStockValue, holdings);
    }

    // AccountStock을 HoldingItem으로 변환
    private AssetResponse.HoldingItem convertToHoldingItem(AccountStock accountStock, BigDecimal currentPrice) {
        int quantity = accountStock.getQuantity();
        BigDecimal averagePrice = accountStock.getAveragePrice();
        BigDecimal totalValue = currentPrice.multiply(BigDecimal.valueOf(quantity));

        return new AssetResponse.HoldingItem(
                accountStock.getStock().getCode(),
                accountStock.getStock().getName(),
                accountStock.getStock().getMarketType(),
                quantity,
                currentPrice,
                averagePrice,
                totalValue
        );
    }

    // 계좌 소유자 확인
    private void ensureAccountOwner(Account account) {
        String memberEmail = getAuthenticatedEmail();
        if (!account.getMember().getEmail().equals(memberEmail)) {
            throw new ForbiddenException("해당 계좌에 대한 권한이 없습니다.");
        }
    }

    // 인증된 사용자 이메일 조회
    private String getAuthenticatedEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ForbiddenException("로그인이 필요합니다.");
        }
        return authentication.getName();
    }
}

