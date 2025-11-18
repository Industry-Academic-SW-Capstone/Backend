package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.MarketOrderCreateRequest;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.dto.PendingOrdersResponse;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.ForbiddenException;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final RedisOrderBookRepository redisOrderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;
    private final OrderHoldRepository orderHoldRepository;
    private final AccountStockRepository accountStockRepository;
    private final RedisMarketDataRepository redisMarketDataRepository;
    private final StockDetailService stockDetailService;

    @Value("${order.market.hold-buffer-rate:0.05}")
    private BigDecimal marketHoldBufferRate;

    // 지정가 주문 생성
    @Transactional
    public OrderResponse createLimitOrder(LimitOrderCreateRequest request) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        ensureAccountOwner(account);

        Stock stock = stockRepository.findById(request.stockCode())
                .orElseThrow(() -> new BadRequestException("존재하지 않는 종목입니다."));

        OrderMethod orderMethod = request.orderMethod();
        if (orderMethod == null) {
            throw new BadRequestException("매수/매도 구분이 필요합니다.");
        }

        Order order = Order.createLimitOrder(
                account,
                stock,
                request.price(),
                request.quantity(),
                orderMethod
        );

        BigDecimal holdAmount = BigDecimal.ZERO;
        if (orderMethod == OrderMethod.BUY) {
            holdAmount = calculateHoldAmount(order); // 주문 금액 계산
            ensureSufficientCash(account, holdAmount); // 주문 가능 현금 확인
            account.increaseHoldAmount(holdAmount); // 홀딩 금액 증가
        } else if (orderMethod == OrderMethod.SELL) {
            applySellHold(order);
        }

        order = orderRepository.save(order);
        if (orderMethod == OrderMethod.BUY) {
            OrderHold orderHold = OrderHold.create(order, account, holdAmount);
            orderHoldRepository.save(orderHold);
        }
        redisOrderBookRepository.addOrder(order);
        orderSubscriptionCoordinator.registerLimitOrder(stock.getCode());

        log.info("지정가 주문 생성 완료: orderId={} stock={} quantity={}", order.getOrderId(), stock.getCode(), order.getQuantity());
        return OrderResponse.from(order);
    }

    // 시장가 주문 생성
    @Transactional
    public OrderResponse createMarketOrder(MarketOrderCreateRequest request) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        ensureAccountOwner(account);

        Stock stock = stockRepository.findById(request.stockCode())
                .orElseThrow(() -> new BadRequestException("존재하지 않는 종목입니다."));

        OrderMethod orderMethod = request.orderMethod();
        if (orderMethod == null) {
            throw new BadRequestException("매수/매도 구분이 필요합니다.");
        }

        Order order = Order.createMarketOrder(
                account,
                stock,
                request.quantity(),
                orderMethod
        );

        // 시장가 주문도 지정가 주문처럼 웹소켓 구독을 먼저 시작
        // 최근 체결가 조회 전에 구독이 시작되어 체결 이벤트를 받을 수 있도록 함
        orderSubscriptionCoordinator.registerLimitOrder(stock.getCode());

        BigDecimal holdAmount = BigDecimal.ZERO;
        if (orderMethod == OrderMethod.SELL) {
            applySellHold(order);
        } else if (orderMethod == OrderMethod.BUY) {
            holdAmount = calculateMarketHoldAmount(stock.getCode(), order.getQuantity());
            ensureSufficientCash(account, holdAmount);
            account.increaseHoldAmount(holdAmount);
        }

        order = orderRepository.save(order);

        if (orderMethod == OrderMethod.BUY) {
            OrderHold orderHold = OrderHold.create(order, account, holdAmount);
            orderHoldRepository.save(orderHold);
        }

        redisOrderBookRepository.addOrder(order);

        log.info("시장가 주문 생성 완료: orderId={} stock={} quantity={}", order.getOrderId(), stock.getCode(), order.getQuantity());
        return OrderResponse.from(order);
    }

    // 주문 취소
    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BadRequestException("주문을 찾을 수 없습니다."));

        ensureAccountOwner(order.getAccount());

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BadRequestException("이미 취소된 주문입니다.");
        }
        if (order.getStatus() == OrderStatus.FILLED) {
            throw new BadRequestException("이미 체결된 주문은 취소할 수 없습니다.");
        }

        order.markCancelled();
        orderRepository.save(order);

        if (order.getRemainingQuantity() > 0) {
            redisOrderBookRepository.removeOrder(order.getOrderId(), order.getStock().getCode(), order.getOrderMethod());
            orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
        }

        if (order.getOrderMethod() == OrderMethod.BUY) {
            releaseBuyHold(order);
        } else if (order.getOrderMethod() == OrderMethod.SELL) {
            releaseSellHold(order);
        }

        log.info("주문 취소 완료: orderId={}", orderId);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BadRequestException("주문을 찾을 수 없습니다."));
        ensureAccountOwner(order.getAccount());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PendingOrdersResponse getPendingOrders(Long accountId) {
        // Account 조회 및 권한 확인
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        ensureAccountOwner(account);

        // 대기 주문 목록 조회 (PENDING, PARTIALLY_FILLED)
        List<Order> pendingOrders = orderRepository.findAllPendingOrdersByAccountId(
                accountId,
                List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)
        );

        // DTO 변환
        List<PendingOrdersResponse.PendingOrderItem> orderItems = pendingOrders.stream()
                .map(order -> {
                    String stockCode = order.getStock().getCode();
                    String stockName = order.getStock().getName();
                    String marketType = order.getStock().getMarketType();
                    BigDecimal price = order.getOrderType() == OrderType.MARKET ? null : order.getPrice();

                    return new PendingOrdersResponse.PendingOrderItem(
                            order.getOrderId(),
                            stockCode,
                            stockName,
                            marketType,
                            order.getOrderMethod(),
                            price,
                            order.getQuantity(),
                            order.getRemainingQuantity(),
                            order.getCreatedAt()
                    );
                })
                .toList();

        log.info("대기 주문 조회 완료: accountId={}, count={}", accountId, orderItems.size());
        return new PendingOrdersResponse(orderItems);
    }

    private BigDecimal calculateHoldAmount(Order order) {
        return order.getPrice().multiply(BigDecimal.valueOf(order.getRemainingQuantity()));
    }

    // 시장가 주문 홀딩 금액 계산
    private BigDecimal calculateMarketHoldAmount(String stockCode, int quantity) {
        // 1. Redis 캐시에서 먼저 조회
        BigDecimal lastPrice = redisMarketDataRepository.getLastPrice(stockCode)
                .orElseGet(() -> {
                    // 2. 캐시에 없으면 KIS API 호출
                    log.info("캐시에 현재가가 없어 KIS API 호출: stockCode={}", stockCode);
                    try {
                        BigDecimal price = stockDetailService.getCurrentPrice(stockCode)
                                .block(java.time.Duration.ofSeconds(5));
                        if (price == null || price.signum() <= 0) {
                            throw new BadRequestException("KIS API에서 현재가를 가져올 수 없습니다.");
                        }
                        // KIS API 결과는 StockDetailService에서 이미 Redis에 저장됨
                        return price;
                    } catch (Exception e) {
                        log.error("KIS API 현재가 조회 실패: stockCode={}", stockCode, e);
                        throw new BadRequestException("최근 체결가 정보를 찾을 수 없습니다.");
                    }
                });
        
        if (lastPrice.signum() <= 0) {
            throw new BadRequestException("최근 체결가가 유효하지 않습니다.");
        }
        BigDecimal bufferRate = Optional.ofNullable(marketHoldBufferRate).orElse(BigDecimal.valueOf(0.05));
        if (bufferRate.signum() < 0) {
            bufferRate = BigDecimal.ZERO;
        }
        BigDecimal bufferFactor = BigDecimal.ONE.add(bufferRate);
        BigDecimal baseAmount = lastPrice.multiply(BigDecimal.valueOf(quantity));
        return baseAmount.multiply(bufferFactor).setScale(2, RoundingMode.UP);
    }

    private void ensureSufficientCash(Account account, BigDecimal holdAmount) {
        if (account.getAvailableCash().compareTo(holdAmount) < 0) {
            throw new BadRequestException("주문 가능 현금이 부족합니다.");
        }
    }

    private void releaseBuyHold(Order order) {
        Optional<OrderHold> holdOpt = orderHoldRepository.findById(order.getOrderId());
        holdOpt.ifPresent(hold -> {
            Account account = order.getAccount();
            BigDecimal holdAmount = hold.getHoldAmount();
            if (holdAmount.signum() > 0) {
                account.decreaseHoldAmount(holdAmount);
            }
            hold.release();
            orderHoldRepository.save(hold);
        });
    }

    private void applySellHold(Order order) {
        AccountStock accountStock = accountStockRepository.findByAccountAndStock(order.getAccount(), order.getStock())
                .orElseThrow(() -> new BadRequestException("보유 중인 종목이 없습니다."));
        accountStock.increaseHoldQuantity(order.getRemainingQuantity());
        accountStockRepository.save(accountStock);
    }

    private void releaseSellHold(Order order) {
        int releaseQuantity = order.getRemainingQuantity();
        if (releaseQuantity <= 0) {
            return;
        }
        accountStockRepository.findByAccountAndStock(order.getAccount(), order.getStock())
                .ifPresent(accountStock -> {
                    accountStock.decreaseHoldQuantity(releaseQuantity);
                    accountStockRepository.save(accountStock);
                });
    }

    private void ensureAccountOwner(Account account) {
        String memberEmail = getAuthenticatedEmail();
        if (!account.getMember().getEmail().equals(memberEmail)) {
            throw new ForbiddenException("해당 계좌에 대한 권한이 없습니다.");
        }
    }

    private String getAuthenticatedEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new ForbiddenException("로그인이 필요합니다.");
        }
        return authentication.getName();
    }
}

