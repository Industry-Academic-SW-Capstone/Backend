package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.repository.RedisOrderBookRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderStatus;
import grit.stockIt.domain.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisDBSyncService {

    private final OrderRepository orderRepository;
    private final RedisOrderBookRepository redisOrderBookRepository;

    @Value("${sync.recent-minutes:5}")
    private int recentMinutes;

    @Value("${sync.batch-size:100}")
    private int batchSize;

    // 1분마다 실행되어 최근 N분 내의 주문을 기준으로 동기화
    @Scheduled(fixedDelay = 60000) // 1분마다
    public void syncRedisWithDB() {
        log.debug("Redis-DB 동기화 시작");
        
        LocalDateTime since = LocalDateTime.now().minusMinutes(recentMinutes);
        
        // 주문 누락 복구 (DB에 있지만 Redis에 없는 주문)
        syncMissingOrders(since);
        
        // 유령 주문 제거 (Redis에 있지만 DB에서 이미 체결된 주문)
        removeGhostOrders();
        
        log.debug("Redis-DB 동기화 완료");
    }

    // DB에 있지만 Redis에 없는 주문을 Redis에 추가
    private void syncMissingOrders(LocalDateTime since) {
        int offset = 0;
        int syncedCount = 0;
        List<OrderStatus> pendingStatuses = List.of(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED);

        while (true) {
            // 최근 N분 내의 PENDING/PARTIALLY_FILLED 주문만 조회 (배치)
            Pageable pageable = PageRequest.of(offset / batchSize, batchSize);
            List<Order> orders = orderRepository.findPendingOrdersSince(since, pendingStatuses, pageable);

            if (orders.isEmpty()) {
                break;
            }

            // Redis에 없는 주문만 추가
            for (Order order : orders) {
                String stockCode = order.getStock().getCode();
                OrderMethod orderMethod = order.getOrderMethod();
                
                if (!redisOrderBookRepository.exists(order.getOrderId(), stockCode, orderMethod)) {
                    try {
                        redisOrderBookRepository.addOrder(order);
                        syncedCount++;
                        log.debug("주문 누락 복구: orderId={} stockCode={} method={}", 
                            order.getOrderId(), stockCode, orderMethod);
                    } catch (Exception e) {
                        log.error("주문 Redis 추가 실패: orderId={} stockCode={}", 
                            order.getOrderId(), stockCode, e);
                    }
                }
            }

            offset += batchSize;
            
            // 마지막 배치가 batchSize보다 작으면 더 이상 조회할 데이터 없음
            if (orders.size() < batchSize) {
                break;
            }
            
            // 메모리 부담을 줄이기 위해 배치 간 짧은 대기
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (syncedCount > 0) {
            log.info("주문 누락 복구 완료: {}건", syncedCount);
        }
    }

    // Redis에 있지만 DB에서 이미 체결된 주문(유령 주문)을 Redis에서 제거
    private void removeGhostOrders() {
        // Redis에서 모든 주문 ID 조회 (종목별로)
        Map<String, Set<Long>> redisOrdersByStock = redisOrderBookRepository.getAllOrderIdsByStock();

        if (redisOrdersByStock.isEmpty()) {
            return;
        }

        int removedCount = 0;

        for (Map.Entry<String, Set<Long>> entry : redisOrdersByStock.entrySet()) {
            String key = entry.getKey();
            Set<Long> redisOrderIds = entry.getValue();

            if (redisOrderIds.isEmpty()) {
                continue;
            }

            String[] parts = key.split(":");
            if (parts.length < 2) {
                continue;
            }
            
            String stockCode = parts[0];
            String methodStr = parts[1];
            
            try {
                OrderMethod orderMethod = OrderMethod.valueOf(methodStr);
                
                // DB에서 이미 체결된 주문 찾기
                List<Long> orderIdList = new ArrayList<>(redisOrderIds);
                List<Long> filledOrderIds = orderRepository.findFilledOrderIdsByIds(
                    orderIdList, OrderStatus.FILLED
                );

                // Redis에서 제거
                for (Long orderId : filledOrderIds) {
                    try {
                        redisOrderBookRepository.removeOrder(orderId, stockCode, orderMethod);
                        removedCount++;
                        log.debug("유령 주문 제거: orderId={} stockCode={} method={}", 
                            orderId, stockCode, orderMethod);
                    } catch (Exception e) {
                        log.error("유령 주문 제거 실패: orderId={} stockCode={}", 
                            orderId, stockCode, e);
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("잘못된 주문 방법: {}", methodStr);
            }
        }

        if (removedCount > 0) {
            log.info("유령 주문 제거 완료: {}건", removedCount);
        }
    }
}

