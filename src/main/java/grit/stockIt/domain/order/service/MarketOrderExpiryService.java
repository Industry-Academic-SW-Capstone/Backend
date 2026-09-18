package grit.stockIt.domain.order.service;

import grit.stockIt.domain.matching.lock.StockMatchingLock;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.domain.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 시장가 주문의 당일 만료. 시장가 홀딩은 그날 상한가로 잡으므로 주문이 다음 매매일로 넘어가면
// 어제 기준으로 묶인 상태가 되어 "홀딩 >= 체결금액" 보장이 깨진다.
// 사용자가 부르는 취소와 달리 인증 주체가 없어 OrderService.cancelOrder 를 쓰지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketOrderExpiryService {

    private static final List<OrderStatus> EXPIRABLE_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final OrderRepository orderRepository;
    private final OrderHoldService orderHoldService;
    private final OrderSubscriptionService orderSubscriptionService;
    private final StockMatchingLock stockMatchingLock;
    private final EntityManager entityManager;

    @Value("${matching.lock.cancel-timeout:3s}")
    private String lockTimeout;

    @Transactional(readOnly = true)
    public List<Long> findExpirableOrderIds() {
        return orderRepository.findExpirableMarketOrderIds(OrderType.MARKET, EXPIRABLE_STATUSES);
    }

    // 주문 하나가 한 트랜잭션이다. 한 건이 실패해도 나머지 만료가 막히지 않는다.
    @Transactional
    public boolean expire(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }

        // 만료도 오더북을 바꾸므로 체결과 같은 락 아래에서 해야 한다. 락을 기다리는 사이
        // 체결이 끝났을 수 있어 다시 읽는다.
        stockMatchingLock.acquire(order.getStock().getCode(), lockTimeout);
        entityManager.refresh(order);

        if (!EXPIRABLE_STATUSES.contains(order.getStatus()) || order.getRemainingQuantity() <= 0) {
            return false;
        }

        order.markCancelled();
        orderRepository.save(order);
        orderSubscriptionService.unsubscribeOnCancel(order);

        if (order.getOrderMethod() == OrderMethod.BUY) {
            orderHoldService.releaseBuyHold(order);
        } else if (order.getOrderMethod() == OrderMethod.SELL) {
            orderHoldService.releaseSellHold(order);
        }

        log.info("시장가 주문 당일 만료: orderId={} stockCode={}", orderId, order.getStock().getCode());
        return true;
    }
}
