package grit.stockIt.domain.execution.service;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.order.entity.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ExecutionService {

    private final ExecutionRepository executionRepository;

    public Execution record(Order order, BigDecimal price, int quantity) {
        Execution execution = Execution.of(order, price, quantity);
        return executionRepository.save(execution);
    }

    // 복구 배치가 통보를 놓친 체결을 다시 정산할 때 쓴다. 연관은 ID 만 읽으므로 주문·계좌·종목을 따로 조회하지 않는다.
    @Transactional(readOnly = true)
    public List<ExecutionReport> findFillReports(List<Long> executionIds) {
        return executionRepository.findAllById(executionIds).stream()
                .map(execution -> ExecutionReport.fill(
                        execution.getExecutionId(),
                        execution.getOrder().getOrderId(),
                        execution.getAccount().getAccountId(),
                        execution.getStock().getCode(),
                        execution.getOrderMethod(),
                        execution.getPrice(),
                        execution.getQuantity(),
                        execution.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
                .toList();
    }
}
