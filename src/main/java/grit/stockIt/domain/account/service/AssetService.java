package grit.stockIt.domain.account.service;

import grit.stockIt.domain.account.dto.AssetResponse;
import grit.stockIt.domain.account.dto.MyStockResponse;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
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
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

// 사용자 자산 조회 서비스
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final StockDetailService stockDetailService;
    private final OrderRepository orderRepository;
    private final ExecutionRepository executionRepository;
    private final StockRepository stockRepository;

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

    // 특정 종목의 보유 정보 및 주문 내역 조회
    @Transactional(readOnly = true)
    public MyStockResponse getMyStock(Long accountId, String stockCode, boolean includeCancelled) {
        // Account 조회 및 권한 확인
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        ensureAccountOwner(account);

        // Stock 조회
        Stock stock = stockRepository.findById(stockCode)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 종목입니다."));

        // 현재가 조회
        BigDecimal fetchedCurrentPrice = stockDetailService.getCurrentPrice(stockCode)
                .timeout(KIS_API_TIMEOUT)
                .onErrorResume(e -> {
                    log.warn("KIS API 현재가 조회 실패: stockCode={}", stockCode, e);
                    return Mono.just(BigDecimal.ZERO);
                })
                .block();

        final BigDecimal currentPrice = (fetchedCurrentPrice != null && fetchedCurrentPrice.signum() > 0)
                ? fetchedCurrentPrice
                : BigDecimal.ZERO;

        // 보유 정보 조회 및 계산
        Optional<AccountStock> accountStockOpt = accountStockRepository.findByAccountAndStock(account, stock);
        MyStockResponse.HoldingInfo holding = accountStockOpt
                .filter(as -> as.getQuantity() > 0)
                .map(as -> calculateHoldingInfo(as, currentPrice))
                .orElse(null);

        // 주문 내역 조회
        List<MyStockResponse.OrderHistoryItem> orderHistory = getOrderHistoryForStock(
                accountId, stockCode, includeCancelled);

        log.info("종목 상세 조회 완료: accountId={}, stockCode={}, hasHolding={}", 
                accountId, stockCode, holding != null);

        return new MyStockResponse(
                stock.getCode(),
                stock.getName(),
                stock.getMarketType(),
                currentPrice,
                holding,
                orderHistory
        );
    }

    private MyStockResponse.HoldingInfo calculateHoldingInfo(AccountStock accountStock, BigDecimal currentPrice) {
        int quantity = accountStock.getQuantity();
        BigDecimal averagePrice = accountStock.getAveragePrice();
        BigDecimal totalValue = currentPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal investmentPrincipal = averagePrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal profitLoss = totalValue.subtract(investmentPrincipal);
        
        BigDecimal profitRate = BigDecimal.ZERO;
        if (investmentPrincipal.compareTo(BigDecimal.ZERO) > 0) {
            profitRate = profitLoss
                    .divide(investmentPrincipal, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        return new MyStockResponse.HoldingInfo(
                averagePrice,
                quantity,
                currentPrice,
                totalValue,
                investmentPrincipal,
                profitLoss,
                profitRate
        );
    }

    private List<MyStockResponse.OrderHistoryItem> getOrderHistoryForStock(
            Long accountId, String stockCode, boolean includeCancelled) {
        
        // 주문 목록 조회
        List<Order> orders = orderRepository.findByAccountIdAndStockCode(
                accountId, stockCode, includeCancelled, OrderStatus.CANCELLED);

        if (orders.isEmpty()) {
            return List.of();
        }

        // 체결된 주문들의 Execution 조회 (체결 가격 계산용)
        List<Long> filledOrderIds = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.FILLED || o.getStatus() == OrderStatus.PARTIALLY_FILLED)
                .map(Order::getOrderId)
                .toList();

        Map<Long, List<Execution>> executionMap = new HashMap<>();
        if (!filledOrderIds.isEmpty()) {
            List<Execution> executions = executionRepository.findByOrderIdIn(filledOrderIds);
            executionMap = executions.stream()
                    .collect(Collectors.groupingBy(e -> e.getOrder().getOrderId()));
        }

        // 주문별 평균 체결 가격 계산 (가중 평균)
        Map<Long, BigDecimal> avgExecutionPriceMap = new HashMap<>();
        Map<Long, Integer> totalExecutedQuantityMap = new HashMap<>();
        
        for (Map.Entry<Long, List<Execution>> entry : executionMap.entrySet()) {
            List<Execution> execs = entry.getValue();
            int totalQuantity = execs.stream().mapToInt(Execution::getQuantity).sum();
            BigDecimal totalAmount = execs.stream()
                    .map(e -> e.getPrice().multiply(BigDecimal.valueOf(e.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            totalExecutedQuantityMap.put(entry.getKey(), totalQuantity);
            if (totalQuantity > 0) {
                avgExecutionPriceMap.put(entry.getKey(), 
                        totalAmount.divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP));
            }
        }

        // 날짜별 그룹화를 위한 연도 추출
        int currentYear = LocalDate.now().getYear();

        // 주문 내역 변환
        List<MyStockResponse.OrderHistoryItem> historyItems = new ArrayList<>();
        for (Order order : orders) {
            int orderYear = order.getCreatedAt().getYear();
            Integer year = (orderYear == currentYear) ? null : orderYear;
            
            String dateStr = order.getCreatedAt().format(DateTimeFormatter.ofPattern("MM.dd"));
            
            BigDecimal executionPrice = avgExecutionPriceMap.get(order.getOrderId());
            int executedQuantity = totalExecutedQuantityMap.getOrDefault(order.getOrderId(), order.getFilledQuantity());
            BigDecimal totalAmount = (executionPrice != null && executedQuantity > 0)
                    ? executionPrice.multiply(BigDecimal.valueOf(executedQuantity))
                    : null;

            historyItems.add(new MyStockResponse.OrderHistoryItem(
                    year,
                    dateStr,
                    order.getOrderId(),
                    order.getOrderMethod(),
                    order.getQuantity(),
                    order.getOrderType().name().equals("MARKET") ? null : order.getPrice(),
                    executionPrice,
                    executedQuantity,
                    totalAmount,
                    order.getStatus(),
                    order.getCreatedAt()
            ));
        }

        return historyItems;
    }
}

