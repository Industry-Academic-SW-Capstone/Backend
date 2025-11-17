package grit.stockIt.domain.execution.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.order.entity.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
@Transactional
public class ExecutionService {

    private final ExecutionRepository executionRepository;

    public Execution record(Order order, BigDecimal price, int quantity) {
        Execution execution = Execution.of(order, price, quantity);
        return executionRepository.save(execution);
    }
}

