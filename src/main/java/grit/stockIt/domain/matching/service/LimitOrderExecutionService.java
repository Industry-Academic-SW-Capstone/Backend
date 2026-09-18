package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.service.ExecutionService;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.lock.StockMatchingLock;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LimitOrderExecutionService {

    private static final List<OrderStatus> ELIGIBLE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final ExecutionService executionService;
    private final OrderRepository orderRepository;
    private final OrderBookRepository orderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;
    private final StockMatchingLock stockMatchingLock;

    private final LimitOrderMatchPlanner matchPlanner = new LimitOrderMatchPlanner();

    // 락을 잡기까지 기다린 시간. 유입이 종목당 상한을 넘으면 이 값이 오른다.
    private final Timer lockWaitTimer;

    // 락을 잡은 뒤 체결이 끝나기까지. 곧 임계 구역의 길이다.
    // 정산이 다른 트랜잭션으로 빠지면서 이 타이머가 재는 구간도 체결까지로 좁아졌다.
    private final Timer criticalSectionTimer;

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    public LimitOrderExecutionService(
            ExecutionService executionService,
            OrderRepository orderRepository,
            OrderBookRepository orderBookRepository,
            OrderSubscriptionCoordinator orderSubscriptionCoordinator,
            StockMatchingLock stockMatchingLock,
            MeterRegistry meterRegistry) {
        this.executionService = executionService;
        this.orderRepository = orderRepository;
        this.orderBookRepository = orderBookRepository;
        this.orderSubscriptionCoordinator = orderSubscriptionCoordinator;
        this.stockMatchingLock = stockMatchingLock;
        this.lockWaitTimer = Timer.builder("matching.lock.wait")
                .description("종목 락을 잡기까지 기다린 시간")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);
        this.criticalSectionTimer = Timer.builder("matching.settlement")
                .description("락을 잡은 뒤 체결이 끝나기까지 — 임계 구역 길이")
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meterRegistry);
    }

    // 종목 락을 잡고 체결한다. 계좌를 보지 않는다 — 체결 수량은 배분 계획과 주문 잔여 수량만으로
    // 정해지므로, 정산에 필요한 계좌 조회·잠금이 이 트랜잭션에 들어오지 않는다.
    //
    // 락은 이 트랜잭션에 묶여 있어 커밋과 함께 풀린다. 커밋은 프록시가 하므로 반환 뒤에 일어난다.
    @Transactional
    public List<Long> fill(String stockCode, LimitOrderFillEvent event) {
        long lockStart = System.nanoTime();
        try {
            stockMatchingLock.acquire(stockCode);
        } finally {
            lockWaitTimer.record(System.nanoTime() - lockStart, TimeUnit.NANOSECONDS);
        }

        long fillStart = System.nanoTime();
        try {
            return doFill(stockCode, event);
        } finally {
            criticalSectionTimer.record(System.nanoTime() - fillStart, TimeUnit.NANOSECONDS);
        }
    }

    private List<Long> doFill(String stockCode, LimitOrderFillEvent event) {
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

        List<Long> executionIds = new ArrayList<>();
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
                executionIds.add(execution.getExecutionId());
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

        return executionIds;
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
