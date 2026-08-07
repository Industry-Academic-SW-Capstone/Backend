package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.MarketOrderCreateRequest;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.dto.PendingOrdersResponse;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

// 주문 오케스트레이션(권한·가격·홀딩·오더북 조율)
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final OrderAuthorizationService orderAuthorizationService;
    private final OrderPricingService orderPricingService;
    private final OrderHoldService orderHoldService;
    private final OrderBookRegistrationService orderBookRegistrationService;

    // 지정가 주문 생성
    @Transactional
    public OrderResponse createLimitOrder(LimitOrderCreateRequest request) {
        Account account = accountRepository.findByIdWithLock(request.accountId())
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        orderAuthorizationService.ensureAccountOwner(account);

        Stock stock = stockRepository.findById(request.stockCode())
                .orElseThrow(() -> new BadRequestException("존재하지 않는 종목입니다."));

        OrderMethod orderMethod = request.orderMethod();
        if (orderMethod == null) {
            throw new BadRequestException("매수/매도 구분이 필요합니다.");
        }

        // 매수 주문인 경우 거래 가능 종목인지 검증
        if (orderMethod == OrderMethod.BUY) {
            orderPricingService.validateStockTradeable(request.stockCode());
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
            holdAmount = orderPricingService.calculateHoldAmount(order); // 주문 금액 계산
            orderHoldService.ensureSufficientCash(account, holdAmount); // 주문 가능 현금 확인
            account.increaseHoldAmount(holdAmount); // 홀딩 금액 증가
        } else if (orderMethod == OrderMethod.SELL) {
            orderHoldService.applySellHold(order);
        }

        Order savedOrder = orderRepository.save(order);
        if (orderMethod == OrderMethod.BUY) {
            orderHoldService.applyBuyHold(savedOrder, account, holdAmount);
        }

        // DB 커밋 후에만 Redis 오더북에 주문 추가 (유령 주문 방지)
        orderBookRegistrationService.registerAfterCommit(savedOrder, stock);

        log.info("지정가 주문 생성 완료: orderId={} stock={} quantity={}", savedOrder.getOrderId(), stock.getCode(), savedOrder.getQuantity());
        return OrderResponse.from(savedOrder);
    }

    // 시장가 주문 생성
    @Transactional
    public OrderResponse createMarketOrder(MarketOrderCreateRequest request) {
        Account account = accountRepository.findByIdWithLock(request.accountId())
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        orderAuthorizationService.ensureAccountOwner(account);

        Stock stock = stockRepository.findById(request.stockCode())
                .orElseThrow(() -> new BadRequestException("존재하지 않는 종목입니다."));

        OrderMethod orderMethod = request.orderMethod();
        if (orderMethod == null) {
            throw new BadRequestException("매수/매도 구분이 필요합니다.");
        }

        // 매수 주문인 경우 거래 가능 종목인지 검증
        if (orderMethod == OrderMethod.BUY) {
            orderPricingService.validateStockTradeable(request.stockCode());
        }

        Order order = Order.createMarketOrder(
                account,
                stock,
                request.quantity(),
                orderMethod
        );

        // 시장가 주문도 지정가 주문처럼 웹소켓 구독을 먼저 시작
        // 최근 체결가 조회 전에 구독이 시작되어 체결 이벤트를 받을 수 있도록 함
        orderBookRegistrationService.preSubscribe(stock.getCode());

        BigDecimal holdAmount = BigDecimal.ZERO;
        if (orderMethod == OrderMethod.SELL) {
            orderHoldService.applySellHold(order);
        } else if (orderMethod == OrderMethod.BUY) {
            holdAmount = orderPricingService.calculateMarketHoldAmount(stock.getCode(), order.getQuantity());
            orderHoldService.ensureSufficientCash(account, holdAmount);
            account.increaseHoldAmount(holdAmount);
        }

        Order savedOrder = orderRepository.save(order);

        if (orderMethod == OrderMethod.BUY) {
            orderHoldService.applyBuyHold(savedOrder, account, holdAmount);
        }

        // DB 커밋 후에만 Redis 오더북에 주문 추가 (유령 주문 방지)
        orderBookRegistrationService.registerAfterCommit(savedOrder, stock);

        log.info("시장가 주문 생성 완료: orderId={} stock={} quantity={}", savedOrder.getOrderId(), stock.getCode(), savedOrder.getQuantity());
        return OrderResponse.from(savedOrder);
    }

    // 주문 취소
    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BadRequestException("주문을 찾을 수 없습니다."));

        orderAuthorizationService.ensureAccountOwner(order.getAccount());

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BadRequestException("이미 취소된 주문입니다.");
        }
        if (order.getStatus() == OrderStatus.FILLED) {
            throw new BadRequestException("이미 체결된 주문은 취소할 수 없습니다.");
        }

        order.markCancelled();
        orderRepository.save(order);

        if (order.getRemainingQuantity() > 0) {
            orderBookRegistrationService.removeOnCancel(order);
        }

        if (order.getOrderMethod() == OrderMethod.BUY) {
            orderHoldService.releaseBuyHold(order);
        } else if (order.getOrderMethod() == OrderMethod.SELL) {
            orderHoldService.releaseSellHold(order);
        }

        log.info("주문 취소 완료: orderId={}", orderId);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findByIdWithStockAndAccount(orderId)
                .orElseThrow(() -> new BadRequestException("주문을 찾을 수 없습니다."));
        orderAuthorizationService.ensureAccountOwner(order.getAccount());
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PendingOrdersResponse getPendingOrders(Long accountId) {
        // Account 조회 및 권한 확인
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

        orderAuthorizationService.ensureAccountOwner(account);

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

}
