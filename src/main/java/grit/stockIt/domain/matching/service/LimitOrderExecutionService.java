package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.service.ExecutionService;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkWriter;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderExecutionService {

    private static final List<OrderStatus> ELIGIBLE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final ExecutionService executionService;
    private final OrderRepository orderRepository;
    private final OrderBookRepository orderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;
    private final ConsumerWatermarkWriter consumerWatermarkWriter;

    private final LimitOrderMatchPlanner matchPlanner = new LimitOrderMatchPlanner();

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    // 위치 기록과 체결이 한 트랜잭션이라 함께 커밋되거나 함께 롤백된다. 비어 있으면 이미 반영한 위치다(재전달).
    // 종목 락은 없다 — 이 종목의 오더북을 바꾸는 명령(체결, 취소, 만료)은 모두 한 파티션에서 이 워커 하나가 차례로 처리한다.
    // 계좌를 보지 않는다 — 체결 수량은 배분 계획과 주문 잔여 수량만으로 정해지므로,
    // 정산에 필요한 계좌 조회·잠금이 이 트랜잭션에 들어오지 않는다.
    @Transactional
    public Optional<List<FilledExecution>> fillOnce(String stockCode, LimitOrderFillEvent event, CommandPosition position) {
        if (!consumerWatermarkWriter.advance(position)) {
            return Optional.empty();
        }
        return Optional.of(doFill(stockCode, event));
    }

    private List<FilledExecution> doFill(String stockCode, LimitOrderFillEvent event) {
        int remainingQuantity = event.quantity();
        if (remainingQuantity <= 0) {
            log.warn("체결 이벤트 수량이 0 이하입니다. eventId={} quantity={}", event.eventId(), event.quantity());
            return List.of();
        }

        List<OrderBookEntry> candidates = orderBookRepository.fetchMatchingEntries(
                stockCode,
                event.orderMethod(),
                event.price(),
                fetchSize
        );

        if (candidates.isEmpty()) {
            log.debug("해당 이벤트에 대응할 주문이 없습니다. stockCode={} price={} method={}",
                    stockCode, event.price(), event.orderMethod());
            return List.of();
        }

        // Priority policy: LimitOrderMatchPlanner.sortByPriority (in-memory), OrderBookRepository.fetchMatchingEntries (SQL ORDER BY + LIMIT), Order sentinel prices
        FillPlan plan = matchPlanner.plan(candidates, event.orderMethod(), remainingQuantity);

        if (plan.allocations().isEmpty()) {
            return List.of();
        }

        Map<Long, FillCommand> fillCommands = new LinkedHashMap<>();
        for (FillPlan.FillAllocation allocation : plan.allocations()) {
            fillCommands.put(allocation.entry().orderId(), new FillCommand(allocation.entry(), allocation.fillQuantity()));
        }
        remainingQuantity = plan.unallocatedQuantity();

        List<Long> filledOrderIds = new ArrayList<>(fillCommands.keySet());
        List<Order> orders = orderRepository.findAllByIdInWithStock(filledOrderIds);
        Map<Long, Order> orderMap = orders.stream()
                .collect(Collectors.toMap(Order::getOrderId, order -> order));

        List<FilledExecution> executions = new ArrayList<>();
        List<Order> updatedOrders = new ArrayList<>();

        for (Long orderId : filledOrderIds) {
            Order order = orderMap.get(orderId);
            FillCommand command = fillCommands.get(orderId);
            if (order == null || command == null) {
                log.warn("DB에서 주문을 찾을 수 없어 건너뜁니다. orderId={}", orderId);
                if (command != null) {
                    orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
                }
                continue;
            }
            if (!isActive(order)) {
                orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
                continue;
            }

            // 배분 계획이 오더북 조회 시점 기준이므로 잔여 수량으로 한 번 더 조인다.
            int desiredFillQuantity = Math.min(command.fillQuantity(), order.getRemainingQuantity());
            if (desiredFillQuantity <= 0) {
                log.debug("잔여 수량 없음. orderId={} remaining={}", orderId, order.getRemainingQuantity());
                continue;
            }

            try {
                order.applyFill(desiredFillQuantity);
                Execution execution = executionService.record(order, event.price(), desiredFillQuantity);

                if (order.getRemainingQuantity() <= 0) {
                    unsubscribeOnFill(order);
                }

                updatedOrders.add(order);
                executions.add(new FilledExecution(execution.getExecutionId(), order.getAccount().getAccountId()));
            } catch (IllegalArgumentException ex) {
                log.error("주문 체결 처리 중 오류 발생. orderId={} fillQuantity={}", orderId, desiredFillQuantity, ex);
                orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
            }
        }

        if (!updatedOrders.isEmpty()) {
            orderRepository.saveAll(updatedOrders);
        }

        if (remainingQuantity > 0) {
            log.debug("체결 이벤트 수량이 일부 남았습니다. eventId={} remaining={} price={}",
                    event.eventId(), remainingQuantity, event.price());
        }

        return executions;
    }

    private boolean isActive(Order order) {
        return ELIGIBLE_STATUSES.contains(order.getStatus());
    }

    private void unsubscribeOnFill(Order order) {
        orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
    }

    private record FillCommand(OrderBookEntry entry, int fillQuantity) {
    }
}
