package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.dto.OrderBookEntry;
import grit.stockIt.domain.matching.entity.ExchangeFill;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.queue.ExecutionReportOutbox;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkWriter;
import grit.stockIt.domain.matching.repository.ExchangeFillRepository;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.matching.repository.OrderBookRepository;
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

    private final ExchangeOrderRepository exchangeOrderRepository;
    private final ExchangeFillRepository exchangeFillRepository;
    private final OrderBookRepository orderBookRepository;
    private final ConsumerWatermarkWriter consumerWatermarkWriter;
    private final ExecutionReportOutbox executionReportOutbox;
    private final FillIdGenerator fillIdGenerator;

    private final LimitOrderMatchPlanner matchPlanner = new LimitOrderMatchPlanner();

    @Value("${matching.limit-order-fetch-size:100}")
    private int fetchSize;

    // 위치 기록과 체결이 한 트랜잭션이라 함께 커밋되거나 함께 롤백된다. 비어 있으면 이미 반영한 위치다(재전달).
    // 종목 락은 없다 — 이 종목의 오더북을 바꾸는 명령(체결, 취소, 만료)은 모두 한 파티션에서 이 워커 하나가 차례로 처리한다.
    // 계좌를 보지 않는다 — 체결 수량은 배분 계획과 주문 잔여 수량만으로 정해지므로,
    // 정산에 필요한 계좌 조회·잠금이 이 트랜잭션에 들어오지 않는다.
    // 체결 결과 통보도 같은 트랜잭션에서 아웃박스에 적는다 — 커밋된 체결이면 통보도 반드시 나간다.
    @Transactional
    public Optional<List<FilledExecution>> fillOnce(String stockCode, LimitOrderFillEvent event, CommandPosition position) {
        if (!consumerWatermarkWriter.advance(position)) {
            return Optional.empty();
        }
        List<FilledExecution> executions = doFill(stockCode, event, position.partition());
        executionReportOutbox.append(executions, System.currentTimeMillis());
        return Optional.of(executions);
    }

    private List<FilledExecution> doFill(String stockCode, LimitOrderFillEvent event, int partition) {
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
        Map<Long, ExchangeOrder> orderMap = exchangeOrderRepository.findAllById(filledOrderIds).stream()
                .collect(Collectors.toMap(ExchangeOrder::getOrderId, order -> order));

        List<FilledExecution> executions = new ArrayList<>();

        for (Long orderId : filledOrderIds) {
            ExchangeOrder order = orderMap.get(orderId);
            FillCommand command = fillCommands.get(orderId);
            if (order == null || !order.isOpen()) {
                log.warn("오더북에 없는 주문이라 건너뜁니다. orderId={}", orderId);
                continue;
            }

            // 배분 계획이 오더북 조회 시점 기준이므로 잔여 수량으로 한 번 더 조인다.
            int desiredFillQuantity = Math.min(command.fillQuantity(), order.getRemainingQuantity());
            if (desiredFillQuantity <= 0) {
                log.debug("잔여 수량 없음. orderId={} remaining={}", orderId, order.getRemainingQuantity());
                continue;
            }

            order.applyFill(desiredFillQuantity);
            long fillId = fillIdGenerator.next(partition);
            ExchangeFill fill = exchangeFillRepository.save(ExchangeFill.of(fillId, order, event.price(), desiredFillQuantity));
            executions.add(new FilledExecution(fill.getFillId(), order.getOrderId(), order.getAccountId(),
                    stockCode, order.getOrderMethod(), event.price(), desiredFillQuantity,
                    order.getFilledQuantity(), order.getRemainingQuantity()));
        }

        if (remainingQuantity > 0) {
            log.debug("체결 이벤트 수량이 일부 남았습니다. eventId={} remaining={} price={}",
                    event.eventId(), remainingQuantity, event.price());
        }

        return executions;
    }

    private record FillCommand(OrderBookEntry entry, int fillQuantity) {
    }
}
