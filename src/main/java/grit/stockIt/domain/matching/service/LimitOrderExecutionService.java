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

import jakarta.persistence.EntityManager;

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
    private final EntityManager entityManager;

    private final LimitOrderMatchPlanner matchPlanner = new LimitOrderMatchPlanner();
    private final OrderCashConstraintCalculator cashConstraintCalculator = new OrderCashConstraintCalculator();

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    @Transactional
    public List<Execution> distributeEvent(String stockCode, LimitOrderFillEvent event) {
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

        List<Execution> executions = new ArrayList<>();
        List<Order> updatedOrders = new ArrayList<>();
        int cancelledQuantity = 0;

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

            // 계좌를 한 번만 조회하고 락을 겁니다 (동일 주문 내 중복 락 획득 방지)
            Account account = accountRepository.findByIdWithLock(order.getAccount().getAccountId())
                    .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다. accountId=" + order.getAccount().getAccountId()));

            // Account 락 획득 후 Order를 DB에서 다시 읽어 최신 상태 확인 (동시성 보호)
            entityManager.refresh(order);
            if (!isActive(order)) {
                log.debug("락 획득 후 주문이 이미 체결/취소됨. orderId={} status={}", orderId, order.getStatus());
                orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
                continue;
            }

            // 다른 트랜잭션이 부분 체결했을 수 있으므로 fillQuantity 재계산
            int freshRemaining = order.getRemainingQuantity();
            int desiredFillQuantity = Math.min(command.fillQuantity(), freshRemaining);
            if (desiredFillQuantity <= 0) {
                log.debug("락 획득 후 잔여 수량 없음. orderId={} remaining={}", orderId, freshRemaining);
                continue;
            }
            int actualFillQuantity = desiredFillQuantity;
            BigDecimal fillPrice = event.price();

            if (order.getOrderMethod() == OrderMethod.BUY) {
                int affordableQuantity = cashConstraintCalculator.calculate(account.getCash(), fillPrice, desiredFillQuantity);
                if (affordableQuantity <= 0) {
                    log.warn("계좌 현금 부족으로 주문을 취소합니다. orderId={} accountId={} requiredUnitPrice={} cash={}",
                            orderId, account.getAccountId(), fillPrice, account.getCash());
                    cancelDueToInsufficientFunds(order, stockCode, account);
                    updatedOrders.add(order);
                    cancelledQuantity += desiredFillQuantity;
                    continue;
                }
                actualFillQuantity = affordableQuantity;
                
                // 미체결 수량 처리
                if (actualFillQuantity < desiredFillQuantity) {
                    cancelledQuantity += (desiredFillQuantity - actualFillQuantity);
                }
            }

            // 매도(SELL)라면 로직 실행 전에 현재 평단가를 미리 조회해서 임시 저장
            BigDecimal currentAvgPrice = BigDecimal.ZERO;
            if (order.getOrderMethod() == OrderMethod.SELL) {
                currentAvgPrice = accountStockRepository
                        .findByAccountAndStock(account, order.getStock())
                        .map(AccountStock::getAveragePrice)
                        .orElse(BigDecimal.ZERO);
            }
            try {
                order.applyFill(actualFillQuantity);
                Execution execution = executionService.record(order, event.price(), actualFillQuantity);
                executions.add(execution);

                // 체결 완료 알림 이벤트 발행
                publishExecutionFilledEvent(execution, order, stockCode, account);

                // 계좌/재고 반영 (여기서 전량 매도 시 AccountStock의 평단가가 0이 될 수 있음)
                handleAccountOnFill(order, event.price(), actualFillQuantity, account);

                updatedOrders.add(order);

                if (order.getRemainingQuantity() <= 0) {
                    finalizeFilledOrder(order, account);

                    TradeCompletionEvent missionEvent = new TradeCompletionEvent(
                            account.getMember().getMemberId(),
                            account.getAccountId(),
                            order.getStock().getCode(),
                            order.getOrderMethod(),
                            order.getQuantity(),         // 총 주문 수량
                            event.price(),               // 체결 가격 (매도 단가)
                            null,
                            null,
                            0,
                            currentAvgPrice
                    );
                    // 미션 리스너는 AFTER_COMMIT이라 발행은 커밋 후 처리를 등록만 한다(체결에 영향 없음).
                    eventPublisher.publishEvent(missionEvent);
                    log.info("미션 시스템 이벤트 발행 (주문 완료 기준): MemberId={}", missionEvent.getMemberId());
                }
            } catch (IllegalArgumentException ex) {
                log.error("주문 체결 처리 중 오류 발생. orderId={} fillQuantity={}", orderId, actualFillQuantity, ex);
                orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
            }
        }

        if (!updatedOrders.isEmpty()) {
            orderRepository.saveAll(updatedOrders);
        }

        // 오더북에 따로 반영할 것이 없다. trade_order 행이 곧 오더북이라 위의 saveAll 로 끝났다.
        //
        // Redis ZSet 오더북을 쓰던 구조에서는 여기서 삭제·수량갱신을 한 번 더 써야 했고,
        // 그 쓰기가 실패하면 중복 체결을 막으려 DB까지 롤백해야 했다. 이중 쓰기가 없으면
        // 그 보상 로직도, 둘이 어긋난 상태를 되돌리는 복구 배치도 필요 없다.

        if (cancelledQuantity > 0) {
            dropResidualQuantity(stockCode, event, cancelledQuantity);
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

    private record FillCommand(OrderBookEntry entry, int fillQuantity) {
    }

    // 주문 체결 처리
    private void handleAccountOnFill(Order order, BigDecimal price, int fillQuantity, Account account) {
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
            updateAccountStockOnBuy(account, order.getStock(), fillQuantity, price);
            return;
        }

        if (order.getOrderMethod() == OrderMethod.SELL) {
            account.increaseCash(fillAmount);
            accountStockRepository.findByAccountAndStock(account, order.getStock())
                    .ifPresentOrElse(
                            accountStock -> {
                                accountStock.decreaseHoldQuantity(fillQuantity);
                                accountStock.decreaseQuantity(fillQuantity);
                            },
                            () -> log.warn("AccountStock을 찾을 수 없습니다. orderId={} accountId={} stockCode={}",
                                    order.getOrderId(), account.getAccountId(), order.getStock().getCode())
                    );
        }
    }

    private void cancelDueToInsufficientFunds(Order order, String stockCode, Account account) {
        order.markCancelled();
        orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal remaining = hold.getHoldAmount();
                    if (remaining.signum() > 0) {
                        account.decreaseHoldAmount(remaining);
                    }
                    hold.release();
                });
    }

    // 배분하지 못한 잔여 수량을 버린다.
    //
    // 매수 주문이 현금 부족으로 취소되면 그만큼의 체결 수량이 갈 곳을 잃는다. 원래는 새 이벤트로
    // 만들어 큐 앞쪽에 되돌렸지만 큐가 없으면 되돌릴 곳이 없다. 같은 트랜잭션에서 재귀 처리하면
    // 임계 구역이 늘어나고 종료 조건도 불분명해진다.
    private void dropResidualQuantity(String stockCode, LimitOrderFillEvent sourceEvent, int quantity) {
        if (quantity <= 0) {
            return;
        }
        log.warn("배분하지 못한 잔여 수량을 버립니다(큐 없음). stockCode={} eventId={} quantity={}",
                stockCode, sourceEvent.eventId(), quantity);
    }

    // 주문 체결 완료 후 주문 해제 처리
    private void finalizeFilledOrder(Order order, Account account) {
        orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal remaining = hold.getHoldAmount();
                    if (remaining.signum() > 0) {
                        account.decreaseHoldAmount(remaining);
                    }
                    hold.release();
                });
    }

    private void updateAccountStockOnBuy(Account account, Stock stock, int fillQuantity, BigDecimal price) {
        accountStockRepository.findByAccountAndStock(account, stock)
                .ifPresentOrElse(
                        accountStock -> {
                            accountStock.increaseQuantity(fillQuantity, price);
                        },
                        () -> accountStockRepository.save(
                                AccountStock.create(account, stock, fillQuantity, price)
                        )
                );
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

