package grit.stockIt.domain.order.service;

import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.util.TransactionHandler;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 오더북 등록/해제 + afterCommit 유령주문 방지 불변식 소유. 무트랜잭션(REQUIRES_NEW 절대 금지 — 불변식 파괴).
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBookRegistrationService {

    private final RedisOrderBookRepository redisOrderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;

    /**
     * DB 커밋 후에만 Redis 오더북에 주문을 등록한다(유령 주문 방지).
     *
     * <p>계약: 반드시 오케스트레이터의 @Transactional 활성 구간 내에서 동기 호출해야 한다.
     * REQUIRED join은 안전하지만 REQUIRES_NEW로 새 트랜잭션을 여는 것은 절대 금지 — afterCommit
     * 불변식(DB 커밋 성공 후에만 오더북 반영)을 파괴한다. 활성 트랜잭션이 없는 상태에서 호출되면
     * {@link TransactionHandler#afterCommit}의 else 분기가 즉시 실행되어 유령 주문이 발생할 수 있다.
     */
    public void registerAfterCommit(Order order, Stock stock) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            log.warn("registerAfterCommit이 활성 트랜잭션 없이 호출됨 - 유령 주문 위험. orderId={} stockCode={}",
                    order.getOrderId(), stock.getCode());
        }
        TransactionHandler.afterCommit(() -> addOrderToRedisAfterCommit(order, stock));
    }

    // 주문 취소 시 잔량이 남아있으면 오더북에서 제거(커밋 전 동기 실행, 기존 동작 보존)
    public void removeOnCancel(Order order) {
        redisOrderBookRepository.removeOrder(order.getOrderId(), order.getStock().getCode(), order.getOrderMethod());
        orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
    }

    // 시장가 주문 저장 전 웹소켓 구독 선등록(체결 이벤트 수신을 위해 저장 전에 구독 시작)
    public void preSubscribe(String stockCode) {
        orderSubscriptionCoordinator.registerLimitOrder(stockCode);
    }

    // DB 커밋 후 Redis 오더북에 주문을 추가하는 메서드
    private void addOrderToRedisAfterCommit(Order order, Stock stock) {
        try {
            redisOrderBookRepository.addOrder(order);
            orderSubscriptionCoordinator.registerLimitOrder(stock.getCode());
        } catch (Exception e) {
            log.error("주문 생성 후 Redis 업데이트 실패. orderId={} stockCode={}",
                    order.getOrderId(), stock.getCode(), e);
            // 복구 로직은 별도 스케줄러에서 처리
        }
    }
}
