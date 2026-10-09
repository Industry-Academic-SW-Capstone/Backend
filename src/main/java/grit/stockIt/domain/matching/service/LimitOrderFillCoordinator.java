package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.queue.CommandPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

// 큐 워커의 체결. 정산은 워커가 정산 큐로 넘긴다.
@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderFillCoordinator {

    private final LimitOrderExecutionService limitOrderExecutionService;

    // 큐 워커 경로의 결과. duplicate 면 이미 반영한 위치라 아무것도 하지 않았다.
    public record QueuedFillOutcome(boolean duplicate, List<FilledExecution> executions) {

        static QueuedFillOutcome alreadyApplied() {
            return new QueuedFillOutcome(true, List.of());
        }

        public int filledOrders() {
            return executions.size();
        }
    }

    // 큐 워커 경로. 체결 실패를 삼키지 않고 던진다 — 워커가 같은 위치를 다시 시도해야 유실이 없다.
    // 정산은 하지 않는다. 워커가 체결 결과를 정산 큐로 넘긴다. 현재가는 큐에 넣을 때 이미 갱신했다.
    public QueuedFillOutcome processQueuedFill(String stockCode, LimitOrderFillEvent event, CommandPosition position) {
        Optional<List<FilledExecution>> executions = limitOrderExecutionService.fillOnce(stockCode, event, position);
        if (executions.isEmpty()) {
            log.info("이미 반영한 명령이라 건너뜁니다. eventId={} position={}", event.eventId(), position);
            return QueuedFillOutcome.alreadyApplied();
        }
        return new QueuedFillOutcome(false, executions.get());
    }
}
