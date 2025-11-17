package grit.stockIt.domain.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.account.entity.AccountStock;
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
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
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
public class LimitOrderMatchingService {

    private static final String LIMIT_EVENT_QUEUE_KEY_PATTERN = "sim:limit:event:%s";
    private static final String LIMIT_LOCK_KEY_PATTERN = "sim:limit:lock:%s";
    private static final List<OrderStatus> ELIGIBLE_STATUSES = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            """
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        return redis.call('del', KEYS[1])
                    else
                        return 0
                    end
                    """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutionService executionService;
    private final OrderRepository orderRepository;
    private final RedisOrderBookRepository redisOrderBookRepository;
    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;
    private final OrderHoldRepository orderHoldRepository;
    private final AccountStockRepository accountStockRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${matching.limit-lock-ttl-seconds:5}")
    private long lockTtlSeconds;

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    @Transactional
    public List<Execution> consumeNextEvent(String stockCode) {
        String lockKey = buildLockKey(stockCode);
        String lockToken = UUID.randomUUID().toString();

        if (!acquireLock(lockKey, lockToken)) {
            log.debug("지정가 매칭 락 획득 실패. stockCode={}", stockCode);
            return List.of();
        }

        try {
            LimitOrderFillEvent event = fetchNextFillEvent(buildEventQueueKey(stockCode));
            if (event == null) {
                return List.of();
            }
            return distributeEvent(stockCode, event);
        } finally {
            releaseLock(lockKey, lockToken);
        }
    }

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

        for (OrderBookEntry entry : orderedEntries) {
            if (remainingQuantity <= 0) {
                break;
            }

            if (entry.isExhausted()) {
                redisOrderBookRepository.removeOrder(entry.orderId(), stockCode, entry.orderMethod());
                continue;
            }

            int fillQuantity = Math.min(entry.remainingQuantity(), remainingQuantity);
            int newRemaining = entry.remainingQuantity() - fillQuantity;
            if (fillQuantity <= 0) {
                continue;
            }

            fillCommands.put(entry.orderId(), new FillCommand(entry, fillQuantity));
            remainingQuantity -= fillQuantity;

            if (newRemaining <= 0) {
                redisOrderBookRepository.removeOrder(entry.orderId(), stockCode, entry.orderMethod());
            } else {
                redisOrderBookRepository.updateRemainingQuantity(entry.orderId(), stockCode, entry.orderMethod(), newRemaining);
            }
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

            int desiredFillQuantity = command.fillQuantity();
            int actualFillQuantity = desiredFillQuantity;
            BigDecimal fillPrice = event.price();

            if (order.getOrderMethod() == OrderMethod.BUY) {
                int affordableQuantity = calculateAffordableQuantity(order, fillPrice, desiredFillQuantity);
                if (affordableQuantity <= 0) {
                    log.warn("계좌 현금 부족으로 주문을 취소합니다. orderId={} accountId={} requiredUnitPrice={} cash={}",
                            orderId, order.getAccount().getAccountId(), fillPrice, order.getAccount().getCash());
                    cancelDueToInsufficientFunds(order, stockCode);
                    updatedOrders.add(order);
                    cancelledQuantity += desiredFillQuantity;
                    continue;
                }
                actualFillQuantity = affordableQuantity;
            }

            try {
                order.applyFill(actualFillQuantity);
                Execution execution = executionService.record(order, fillPrice, actualFillQuantity); // 체결 저장
                executions.add(execution);
                handleAccountOnFill(order, fillPrice, actualFillQuantity);
                updatedOrders.add(order);
                
                // 체결 완료 이벤트 발행 (Event-Driven 아키텍처)
                publishExecutionFilledEvent(execution, order, stockCode);
                if (order.getOrderMethod() == OrderMethod.BUY && actualFillQuantity < desiredFillQuantity) {
                    int shortfall = desiredFillQuantity - actualFillQuantity;
                    cancelledQuantity += shortfall;
                    cancelDueToInsufficientFunds(order, stockCode);
                    continue;
                }
                if (order.getRemainingQuantity() <= 0) {
                    finalizeFilledOrder(order);
                }
            } catch (IllegalArgumentException ex) {
                log.error("주문 체결 처리 중 오류 발생. orderId={} fillQuantity={}", orderId, command.fillQuantity(), ex);
                redisOrderBookRepository.removeOrder(orderId, stockCode, order.getOrderMethod());
                orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
            }
        }

        if (!updatedOrders.isEmpty()) {
            orderRepository.saveAll(updatedOrders);
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
    private void handleAccountOnFill(Order order, BigDecimal price, int fillQuantity) {
        BigDecimal fillAmount = price.multiply(BigDecimal.valueOf(fillQuantity));

        if (order.getOrderMethod() == OrderMethod.BUY) {
            order.getAccount().decreaseCash(fillAmount);
            orderHoldRepository.findById(order.getOrderId())
                    .ifPresentOrElse(hold -> {
                        order.getAccount().decreaseHoldAmount(fillAmount);
                        hold.decreaseHoldAmount(fillAmount);
                        orderHoldRepository.save(hold);
                    }, () -> log.warn("OrderHold를 찾을 수 없습니다. orderId={}", order.getOrderId()));
            updateAccountStockOnBuy(order, fillQuantity, price);
            return;
        }

        if (order.getOrderMethod() == OrderMethod.SELL) {
            order.getAccount().increaseCash(fillAmount);
            accountStockRepository.findByAccountAndStock(order.getAccount(), order.getStock())
                    .ifPresentOrElse(
                            accountStock -> {
                                accountStock.decreaseHoldQuantity(fillQuantity);
                                accountStock.decreaseQuantity(fillQuantity);
                                accountStockRepository.save(accountStock);
                            },
                            () -> log.warn("AccountStock을 찾을 수 없습니다. orderId={} accountId={} stockCode={}",
                                    order.getOrderId(), order.getAccount().getAccountId(), order.getStock().getCode())
                    );
        }
    }

    private int calculateAffordableQuantity(Order order, BigDecimal price, int desiredQuantity) {
        if (price == null || price.signum() <= 0) {
            return 0;
        }
        BigDecimal cash = order.getAccount().getCash();
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

    private void cancelDueToInsufficientFunds(Order order, String stockCode) {
        order.markCancelled();
        redisOrderBookRepository.removeOrder(order.getOrderId(), stockCode, order.getOrderMethod());
        orderSubscriptionCoordinator.unregisterLimitOrder(stockCode);
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal remaining = hold.getHoldAmount();
                    if (remaining.signum() > 0) {
                        order.getAccount().decreaseHoldAmount(remaining);
                    }
                    hold.release();
                    orderHoldRepository.save(hold);
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
    private void finalizeFilledOrder(Order order) {
        orderSubscriptionCoordinator.unregisterLimitOrder(order.getStock().getCode());
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal remaining = hold.getHoldAmount();
                    if (remaining.signum() > 0) {
                        order.getAccount().decreaseHoldAmount(remaining);
                    }
                    hold.release();
                    orderHoldRepository.save(hold);
                });
    }

    private void updateAccountStockOnBuy(Order order, int fillQuantity, BigDecimal price) {
        accountStockRepository.findByAccountAndStock(order.getAccount(), order.getStock())
                .ifPresentOrElse(
                        accountStock -> {
                            accountStock.increaseQuantity(fillQuantity, price);
                            accountStockRepository.save(accountStock);
                        },
                        () -> accountStockRepository.save(
                                AccountStock.create(order.getAccount(), order.getStock(), fillQuantity, price)
                        )
                );
    }

    private LimitOrderFillEvent fetchNextFillEvent(String queueKey) {
        try {
            String rawEvent = redisTemplate.opsForList().leftPop(queueKey);
            if (rawEvent == null) {
                return null;
            }
            return objectMapper.readValue(rawEvent, LimitOrderFillEvent.class);
        } catch (DataAccessException e) {
            log.error("Redis 접근 중 오류 발생. queueKey={}", queueKey, e);
            throw e;
        } catch (Exception e) {
            log.error("지정가 매칭 이벤트 파싱 실패. queueKey={}", queueKey, e);
            return null;
        }
    }

    private boolean acquireLock(String lockKey, String lockToken) {
        return Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(
                        lockKey,
                        lockToken,
                        Duration.ofSeconds(lockTtlSeconds)
                )
        );
    }

    private void releaseLock(String lockKey, String lockToken) {
        redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockToken);
    }

    private String buildEventQueueKey(String stockCode) {
        return LIMIT_EVENT_QUEUE_KEY_PATTERN.formatted(stockCode);
    }

    private String buildLockKey(String stockCode) {
        return LIMIT_LOCK_KEY_PATTERN.formatted(stockCode);
    }

    /**
     * 체결 완료 이벤트 발행
     * Event-Driven 아키텍처: ExecutionNotificationService와 완전히 분리
     */
    private void publishExecutionFilledEvent(Execution execution, Order order, String stockCode) {
        try {
            ExecutionFilledEvent event = new ExecutionFilledEvent(
                    execution.getExecutionId(),
                    order.getOrderId(),
                    order.getAccount().getAccountId(),
                    order.getAccount().getMember().getMemberId(),  // Member ID 추가
                    stockCode,
                    execution.getStock().getName(),
                    execution.getPrice(),
                    execution.getQuantity(),
                    execution.getOrderMethod().name()  // BUY, SELL
            );
            
            // 체결 완료 이벤트 발행
            eventPublisher.publishEvent(event);
            log.debug("체결 완료 이벤트 발행: executionId={}, memberId={}", execution.getExecutionId(), event.memberId());
        } catch (Exception e) {
            log.error("체결 완료 이벤트 발행 실패: executionId={}", execution.getExecutionId(), e);
        }
    }
}

