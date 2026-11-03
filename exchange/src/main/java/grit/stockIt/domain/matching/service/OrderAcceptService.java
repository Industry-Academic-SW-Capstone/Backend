package grit.stockIt.domain.matching.service;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.contract.ExecutionReportType;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.domain.matching.queue.ExecutionReportOutbox;
import grit.stockIt.domain.matching.queue.MatchingCommand;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkWriter;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 증권사가 보낸 주문을 오더북에 넣고 접수(ACCEPTED)를 알린다. 시간 우선은 여기 도착한 순서다.
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAcceptService {

    private final ExchangeOrderRepository exchangeOrderRepository;
    private final ConsumerWatermarkWriter consumerWatermarkWriter;
    private final ExecutionReportOutbox executionReportOutbox;

    // 같은 주문이 다시 와도(증권사 아웃박스의 재전송은 새 위치라 워터마크로 걸러지지 않는다) 주문 번호로 한 번만 넣는다.
    @Transactional
    public void acceptOnce(MatchingCommand command, CommandPosition position) {
        if (!consumerWatermarkWriter.advance(position)) {
            return;
        }
        if (exchangeOrderRepository.existsById(command.orderId())) {
            log.info("이미 오더북에 있는 주문이라 건너뜁니다. orderId={}", command.orderId());
            return;
        }

        if (!acceptable(command)) {
            log.warn("받을 수 없는 주문이라 거부합니다. command={}", command);
            executionReportOutbox.append(ExecutionReport.of(ExecutionReportType.REJECTED, command.orderId(),
                    command.accountId(), command.stockCode(), command.orderMethod(), Math.max(0, command.quantity()),
                    System.currentTimeMillis()));
            return;
        }

        exchangeOrderRepository.save(ExchangeOrder.accept(command.orderId(), command.accountId(), command.stockCode(),
                command.orderMethod(), command.orderType(), command.price(), command.quantity()));
        executionReportOutbox.append(ExecutionReport.of(ExecutionReportType.ACCEPTED, command.orderId(),
                command.accountId(), command.stockCode(), command.orderMethod(), 0, System.currentTimeMillis()));
    }

    // 호가단위·가격제한폭 같은 거래소 규칙을 더하면 여기서 거부한다.
    private static boolean acceptable(MatchingCommand command) {
        return command.orderType() != null && command.price() != null && command.price().signum() > 0
                && command.quantity() > 0;
    }
}
