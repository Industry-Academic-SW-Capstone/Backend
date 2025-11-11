package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.stock.repository.StockRepository;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final RedisOrderBookRepository redisOrderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;

    /**
     * 지정가 주문 생성
     */
    @Transactional
    public OrderResponse createLimitOrder(LimitOrderCreateRequest request) {
        Account account = accountRepository.findById(request.accountId())
                .orElseThrow(() -> new BadRequestException("계좌를 찾을 수 없습니다."));

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

        order = orderRepository.save(order);
        redisOrderBookRepository.addOrder(order);
        orderSubscriptionCoordinator.registerLimitOrder(stock.getCode());

        log.info("지정가 주문 생성 완료: orderId={} stock={} quantity={}", order.getOrderId(), stock.getCode(), order.getQuantity());
        return OrderResponse.from(order);
    }

    /**
     * 주문 취소
     */
    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BadRequestException("주문을 찾을 수 없습니다."));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BadRequestException("이미 취소된 주문입니다.");
        }
        if (order.getStatus() == OrderStatus.FILLED) {
            throw new BadRequestException("이미 체결된 주문은 취소할 수 없습니다.");
        }

        order.markCancelled();
        orderRepository.save(order);

        if (order.getOrderType() == OrderType.LIMIT && order.getRemainingQuantity() > 0) {
            redisOrderBookRepository.removeOrder(order.getOrderId(), order.getStock().getCode(), order.getOrderMethod());
            orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
        }

        log.info("주문 취소 완료: orderId={}", orderId);
        return OrderResponse.from(order);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public OrderResponse getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BadRequestException("주문을 찾을 수 없습니다."));
        return OrderResponse.from(order);
    }
}

