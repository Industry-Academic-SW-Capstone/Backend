package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.service.ExecutionService;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import grit.stockIt.domain.order.event.TradeCompletionEvent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final OrderHoldRepository orderHoldRepository;
    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final ApplicationEventPublisher eventPublisher;

    private final LimitOrderMatchPlanner matchPlanner = new LimitOrderMatchPlanner();

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    @Transactional
    public List<Execution> distributeEvent(String stockCode, LimitOrderFillEvent event) {
        List<FillResult> results = fill(stockCode, event);
        settle(stockCode, event, results);
        return results.stream().map(FillResult::execution).toList();
    }

    // 체결. 계좌를 보지 않는다 — 체결 수량은 배분 계획과 주문 잔여 수량만으로 정해진다.
    private List<FillResult> fill(String stockCode, LimitOrderFillEvent event) {
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

        List<FillResult> results = new ArrayList<>();
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

                boolean fullyFilled = order.getRemainingQuantity() <= 0;
                if (fullyFilled) {
                    unsubscribeOnFill(order);
                }

                updatedOrders.add(order);
                results.add(new FillResult(order, execution, desiredFillQuantity, fullyFilled));
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

        return results;
    }

    // 정산. 계좌를 잠그고 현금·보유종목·홀딩을 반영한다.
    private void settle(String stockCode, LimitOrderFillEvent event, List<FillResult> results) {
        for (FillResult result : results) {
            Order order = result.order();
            try {
                Account account = accountRepository.findByIdWithLock(order.getAccount().getAccountId())
                        .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다. accountId=" + order.getAccount().getAccountId()));

                AccountStock holding = accountStockRepository
                        .findByAccountAndStock(account, order.getStock())
                        .orElse(null);

                // 전량 매도면 handleAccountOnFill 이 평단가를 0으로 만들므로 그 전에 담아둔다.
                BigDecimal currentAvgPrice = order.getOrderMethod() == OrderMethod.SELL && holding != null
                        ? holding.getAveragePrice()
                        : BigDecimal.ZERO;

                publishExecutionFilledEvent(result.execution(), order, stockCode, account);
                handleAccountOnFill(order, event.price(), result.fillQuantity(), account, holding);

                if (result.fullyFilled()) {
                    releaseRemainingHold(order, account);
                    publishTradeCompletionEvent(order, event, account, currentAvgPrice);
                }
            } catch (IllegalArgumentException ex) {
                log.error("주문 정산 처리 중 오류 발생. orderId={} fillQuantity={}",
                        order.getOrderId(), result.fillQuantity(), ex);
                orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
            }
        }
    }

    private boolean isActive(Order order) {
        return ELIGIBLE_STATUSES.contains(order.getStatus());
    }

    private record FillCommand(OrderBookEntry entry, int fillQuantity) {
    }

    // fill 이 정산에 넘기는 결과. 트랜잭션을 가르면 엔티티가 준영속이 되므로
    // 정산에 필요한 값은 체결 시점에 담아 넘긴다.
    private record FillResult(Order order, Execution execution, int fillQuantity, boolean fullyFilled) {
    }

    // 주문 체결 처리. holding 은 호출자가 이미 읽은 보유종목이며, 없으면 null 이다.
    private void handleAccountOnFill(Order order, BigDecimal price, int fillQuantity,
                                     Account account, AccountStock holding) {
        BigDecimal fillAmount = price.multiply(BigDecimal.valueOf(fillQuantity));

        if (order.getOrderMethod() == OrderMethod.BUY) {
            account.decreaseCash(fillAmount);
            orderHoldRepository.findById(order.getOrderId())
                    .ifPresentOrElse(
                            hold -> {
                                account.decreaseHoldAmount(fillAmount);
                                hold.decreaseHoldAmount(fillAmount);
                            },
                            () -> log.warn("OrderHold를 찾을 수 없습니다. orderId={}", order.getOrderId())
                    );
            updateAccountStockOnBuy(account, order.getStock(), fillQuantity, price, holding);
            return;
        }

        if (order.getOrderMethod() == OrderMethod.SELL) {
            account.increaseCash(fillAmount);
            if (holding == null) {
                log.warn("AccountStock을 찾을 수 없습니다. orderId={} accountId={} stockCode={}",
                        order.getOrderId(), account.getAccountId(), order.getStock().getCode());
                return;
            }
            holding.decreaseHoldQuantity(fillQuantity);
            holding.decreaseQuantity(fillQuantity);
        }
    }

    private void unsubscribeOnFill(Order order) {
        orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
    }

    private void releaseRemainingHold(Order order, Account account) {
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal remaining = hold.getHoldAmount();
                    if (remaining.signum() > 0) {
                        account.decreaseHoldAmount(remaining);
                    }
                    hold.release();
                });
    }

    private void updateAccountStockOnBuy(Account account, Stock stock, int fillQuantity,
                                         BigDecimal price, AccountStock holding) {
        if (holding == null) {
            accountStockRepository.save(AccountStock.create(account, stock, fillQuantity, price));
            return;
        }
        holding.increaseQuantity(fillQuantity, price);
    }

    private void publishTradeCompletionEvent(Order order, LimitOrderFillEvent event,
                                            Account account, BigDecimal currentAvgPrice) {
        TradeCompletionEvent missionEvent = new TradeCompletionEvent(
                account.getMember().getMemberId(),
                account.getAccountId(),
                order.getStock().getCode(),
                order.getOrderMethod(),
                order.getQuantity(),
                event.price(),
                null,
                null,
                0,
                currentAvgPrice
        );
        // 미션 리스너는 AFTER_COMMIT이라 발행은 커밋 후 처리를 등록만 한다(체결에 영향 없음).
        eventPublisher.publishEvent(missionEvent);
        log.info("미션 시스템 이벤트 발행 (주문 완료 기준): MemberId={}", missionEvent.getMemberId());
    }

    // 체결 완료 이벤트 발행
    private void publishExecutionFilledEvent(Execution execution, Order order, String stockCode, Account account) {
        try {
            ExecutionFilledEvent event = new ExecutionFilledEvent(
                    execution.getExecutionId(),
                    order.getOrderId(),
                    account.getAccountId(),
                    account.getMember().getMemberId(),  // Member ID 추가
                    account.getContest().getContestId(),  // Contest ID 추가
                    account.getContest().getContestName(),  // Contest 이름 추가
                    stockCode,
                    execution.getStock().getName(),
                    execution.getPrice(),
                    execution.getQuantity(),
                    execution.getOrderMethod().name()  // BUY, SELL
            );

            // 체결 완료 이벤트 발행
            eventPublisher.publishEvent(event);
            log.debug("체결 완료 이벤트 발행: executionId={}, memberId={}, contestId={}", 
                    execution.getExecutionId(), event.memberId(), event.contestId());
        } catch (Exception e) {
            log.error("체결 완료 이벤트 발행 실패: executionId={}", execution.getExecutionId(), e);
        }
    }
}

