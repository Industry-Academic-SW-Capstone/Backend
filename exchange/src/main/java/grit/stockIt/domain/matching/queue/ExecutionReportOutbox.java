package grit.stockIt.domain.matching.queue;

import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.matching.service.FilledExecution;
import grit.stockIt.global.outbox.OutboxRecord;
import grit.stockIt.global.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 결과 통보를 그 결과를 만든 트랜잭션 안에서 아웃박스에 적는다. 계좌 ID 가 키다 — 한 계좌의 통보는 한 파티션에 차례로 쌓인다.
@Component
@RequiredArgsConstructor
public class ExecutionReportOutbox {

    private final OutboxWriter outboxWriter;

    public void append(List<FilledExecution> executions, long filledAt) {
        outboxWriter.append(executions.stream().map(execution -> toRecord(execution.toReport(filledAt))).toList());
    }

    public void append(ExecutionReport report) {
        outboxWriter.append(List.of(toRecord(report)));
    }

    private static OutboxRecord toRecord(ExecutionReport report) {
        return new OutboxRecord(ExchangeTopics.REPORTS, String.valueOf(report.accountId()), report);
    }
}
