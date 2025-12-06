package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.service.ExecutionService;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.math.RoundingMode;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderExecutionService {

    private static final String LIMIT_EVENT_QUEUE_KEY_PATTERN = "sim:limit:event:%s";
    private static final List<OrderStatus> ELIGIBLE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

    private final ExecutionService executionService;
    private final OrderRepository orderRepository;
    private final RedisOrderBookRepository redisOrderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;
    private final OrderHoldRepository orderHoldRepository;
    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    @Transactional
    public List<Execution> distributeEvent(String stockCode, LimitOrderFillEvent event) {
        int remainingQuantity = event.quantity();
        if (remainingQuantity <= 0) {
            log.warn("체결 이벤트 수량이 0 이하입니다. eventId={} quantity={}", event.eventId(), event.quantity());
            return List.of();
        }

        List<OrderBookEntry> candidates = redisOrderBookRepository.fetchMatchingEntries(
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

        List<OrderBookEntry> orderedEntries = sortByPriority(candidates, event.orderMethod());
        Map<Long, FillCommand> fillCommands = new LinkedHashMap<>();

        // Redis에서 주문 조회 및 fillCommands 생성 (읽기만 수행)
        for (OrderBookEntry entry : orderedEntries) {
            if (remainingQuantity <= 0) {
                break;
            }

            if (entry.isExhausted()) {
                // 소진된 주문은 나중에 DB 업데이트 후 Redis에서 삭제
                continue;
            }

            int fillQuantity = Math.min(entry.remainingQuantity(), remainingQuantity);
            if (fillQuantity <= 0) {
                continue;
            }

            fillCommands.put(entry.orderId(), new FillCommand(entry, fillQuantity));
            remainingQuantity -= fillQuantity;
        }

        if (fillCommands.isEmpty()) {
            return List.of();
        }

        List<Long> filledOrderIds = new ArrayList<>(fillCommands.keySet());
        List<Order> orders = orderRepository.findAllById(filledOrderIds);
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
                    redisOrderBookRepository.removeOrder(orderId, stockCode, command.entry().orderMethod());
                    orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
                }
                continue;
            }
            if (!isActive(order)) {
                redisOrderBookRepository.removeOrder(orderId, stockCode, order.getOrderMethod());
                orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
                continue;
            }

            // 계좌를 한 번만 조회하고 락을 겁니다 (동일 주문 내 중복 락 획득 방지)
            Account account = accountRepository.findByIdWithLock(order.getAccount().getAccountId())
                    .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다. accountId=" + order.getAccount().getAccountId()));

            int desiredFillQuantity = command.fillQuantity();
            int actualFillQuantity = desiredFillQuantity;
            BigDecimal fillPrice = event.price();

            if (order.getOrderMethod() == OrderMethod.BUY) {
                int affordableQuantity = calculateAffordableQuantity(account, fillPrice, desiredFillQuantity);
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

                    try {
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
                        eventPublisher.publishEvent(missionEvent);
                        log.info("미션 시스템 이벤트 발행 (주문 완료 기준): MemberId={}", missionEvent.getMemberId());

                    } catch (Exception e) {
                        log.error("미션 이벤트 발행 실패: orderId={}", order.getOrderId(), e);
                    }
                }
            } catch (IllegalArgumentException ex) {
                log.error("주문 체결 처리 중 오류 발생. orderId={} fillQuantity={}", orderId, actualFillQuantity, ex);
                redisOrderBookRepository.removeOrder(orderId, stockCode, order.getOrderMethod());
                orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
            }
        }

        if (!updatedOrders.isEmpty()) {
            orderRepository.saveAll(updatedOrders);
        }

        // 트랜잭션 내부 마지막 줄로 DB 업데이트가 성공한 후에만 Redis에서 주문 삭제/수량 감소
        // Redis 실패 시 예외를 던져서 DB 롤백하여 중복 체결 방지
        try {
            for (Long orderId : filledOrderIds) {
                Order order = orderMap.get(orderId);
                if (order == null) {
                    continue;
                }

                if (order.getRemainingQuantity() <= 0) {
                    // 전량 체결된 주문은 Redis에서 삭제
                    redisOrderBookRepository.removeOrder(orderId, stockCode, order.getOrderMethod());
                } else {
                    // 부분 체결된 주문은 수량 업데이트
                    redisOrderBookRepository.updateRemainingQuantity(
                        orderId, stockCode, order.getOrderMethod(), order.getRemainingQuantity()
                    );
                }
            }

            // 소진된 주문도 Redis에서 삭제
            for (OrderBookEntry entry : orderedEntries) {
                if (entry.isExhausted()) {
                    redisOrderBookRepository.removeOrder(entry.orderId(), stockCode, entry.orderMethod());
                }
            }
        } catch (Exception e) {
            log.error("Redis 업데이트 실패로 DB 롤백: stockCode={} eventId={}", 
                stockCode, event.eventId(), e);
            throw e; // 예외를 던져서 DB 롤백
        }

        if (cancelledQuantity > 0) {
            enqueueResidualEvent(stockCode, event, cancelledQuantity);
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

    private List<OrderBookEntry> sortByPriority(List<OrderBookEntry> entries, OrderMethod takerMethod) {
        Comparator<OrderBookEntry> comparator = Comparator.comparing(OrderBookEntry::price);
        if (takerMethod == OrderMethod.SELL) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparingLong(OrderBookEntry::createdAtEpochMillis);
        return entries.stream().sorted(comparator).toList();
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

    private int calculateAffordableQuantity(Account account, BigDecimal price, int desiredQuantity) {
        if (price == null || price.signum() <= 0) {
            return 0;
        }
        BigDecimal cash = account.getCash();
        if (cash.compareTo(price) < 0) {
            return 0;
        }
        BigDecimal affordableRaw = cash.divide(price, 0, RoundingMode.FLOOR);
        if (affordableRaw.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        int affordable = affordableRaw.min(BigDecimal.valueOf(desiredQuantity)).intValue();
        return Math.min(affordable, desiredQuantity);
    }

    private void cancelDueToInsufficientFunds(Order order, String stockCode, Account account) {
        order.markCancelled();
        redisOrderBookRepository.removeOrder(order.getOrderId(), stockCode, order.getOrderMethod());
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

    // 잔여 체결 이벤트 재큐잉
    private void enqueueResidualEvent(String stockCode, LimitOrderFillEvent sourceEvent, int quantity) {
        if (quantity <= 0) {
            return;
        }
        LimitOrderFillEvent residualEvent = new LimitOrderFillEvent(
                UUID.randomUUID().toString(),
                sourceEvent.orderMethod(),
                sourceEvent.price(),
                quantity,
                sourceEvent.eventTimestamp()
        );
        try {
            String payload = objectMapper.writeValueAsString(residualEvent);
            redisTemplate.opsForList().leftPush(buildEventQueueKey(stockCode), payload);
        } catch (Exception e) {
            log.error("잔여 체결 이벤트 재큐잉 실패. stockCode={} event={}", stockCode, residualEvent, e);
        }
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

    private String buildEventQueueKey(String stockCode) {
        return LIMIT_EVENT_QUEUE_KEY_PATTERN.formatted(stockCode);
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

