package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.matching.lock.StockMatchingLock;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.global.util.TransactionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// 체결 워커가 꺼낸 취소·만료 명령을 처리한다. 같은 종목의 체결과 한 줄에서 도착 순서대로 처리되므로,
// 명령을 넣은 뒤 먼저 도착한 체결이 주문을 채웠으면 취소되지 않는다.
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final OrderHoldService orderHoldService;
    private final OrderSubscriptionService orderSubscriptionService;
    private final StockMatchingLock stockMatchingLock;

    // 위치 기록과 취소가 한 트랜잭션이라 함께 커밋되거나 함께 롤백된다.
    // 같은 주문의 취소가 두 번 들어와도 두 번째는 상태를 보고 아무것도 하지 않는다.
    @Transactional
    public QueuedCancelOutcome cancelOnce(String stockCode, Long orderId, CommandPosition position) {
        return endOnce(stockCode, orderId, position, "주문 취소");
    }

    // 시장가 주문 당일 만료. 시장가 홀딩은 그날 상한가로 잡으므로 다음 매매일로 넘어가면 "홀딩 >= 체결금액" 보장이 깨진다.
    // 처리는 취소와 같다 — 권한은 접수 단계에서만 보므로 워커에는 원래 없다.
    @Transactional
    public QueuedCancelOutcome expireOnce(String stockCode, Long orderId, CommandPosition position) {
        return endOnce(stockCode, orderId, position, "시장가 주문 당일 만료");
    }

    private QueuedCancelOutcome endOnce(String stockCode, Long orderId, CommandPosition position, String action) {
        boolean firstDelivery = stockMatchingLock.acquireAndAdvance(
                stockCode, position.topic(), position.partition(), position.offset());
        if (!firstDelivery) {
            return QueuedCancelOutcome.DUPLICATE;
        }

        Optional<Order> found = orderRepository.findById(orderId);
        if (found.isEmpty() || !isCancellable(found.get())) {
            log.info("{} 대상이 아니라 건너뜁니다. orderId={} status={}",
                    action, orderId, found.map(Order::getStatus).orElse(null));
            return QueuedCancelOutcome.NOT_CANCELLABLE;
        }

        Order order = found.get();
        order.markCancelled();
        releaseHold(order);
        if (order.getRemainingQuantity() > 0) {
            TransactionHandler.afterCommit(() -> orderSubscriptionService.unsubscribeOnCancel(order));
        }
        log.info("{} 완료: orderId={} stockCode={}", action, orderId, stockCode);
        return QueuedCancelOutcome.CANCELLED;
    }

    private boolean isCancellable(Order order) {
        return order.getStatus() != OrderStatus.CANCELLED && order.getStatus() != OrderStatus.FILLED;
    }

    // 홀딩을 고치기 전에 계좌를 잠근다. 같은 계좌의 정산이 현금·홀딩을 동시에 바꾸면 늦게 커밋한 쪽이
    // 계좌 행 전체를 덮어써 한쪽 변경이 사라진다.
    private void releaseHold(Order order) {
        accountRepository.findByIdWithLock(order.getAccount().getAccountId());
        if (order.getOrderMethod() == OrderMethod.BUY) {
            orderHoldService.releaseBuyHold(order);
        } else if (order.getOrderMethod() == OrderMethod.SELL) {
            orderHoldService.releaseSellHold(order);
        }
    }
}
