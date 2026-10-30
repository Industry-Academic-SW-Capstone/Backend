package grit.stockIt.domain.matching.queue;

import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.global.outbox.OutboxRecord;
import grit.stockIt.global.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 체결 결과 통보를 체결 트랜잭션 안에서 아웃박스에 적는다. 계좌 ID 가 키다 — 한 계좌의 통보는 한 파티션에 차례로 쌓인다.
@Component
@RequiredArgsConstructor
public class ExecutionReportOutbox {

    private final OutboxWriter outboxWriter;

    public void append(List<FilledExecution> executions, long filledAt) {
        outboxWriter.append(executions.stream()
                .map(execution -> new OutboxRecord(ExchangeTopics.REPORTS, String.valueOf(execution.accountId()),
                        execution.toReport(filledAt)))
                .toList());
    }
}
