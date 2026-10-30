package grit.stockIt.domain.order.service;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.repository.HoldReleaseWriter;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.global.util.TransactionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 거래소가 알린 취소·만료로 주문에 묶어 둔 현금·주식을 푼다. 계좌를 고치는 일이라 증권사가 한다.
// 통보는 계좌 키로 도착 순서대로 처리되므로, 같은 주문의 앞선 체결 통보는 이미 정산된 뒤다.
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderHoldReleaseService {

    private final HoldReleaseWriter holdReleaseWriter;
    private final AccountRepository accountRepository;
    private final OrderRepository orderRepository;
    private final OrderHoldService orderHoldService;
    private final OrderSubscriptionService orderSubscriptionService;

    @Transactional
    public void release(ExecutionReport report) {
        if (!holdReleaseWriter.markReleased(report.orderId())) {
            log.info("이미 홀딩을 푼 주문이라 건너뜁니다. type={} orderId={}", report.type(), report.orderId());
            return;
        }

        // 홀딩을 고치기 전에 계좌를 잠근다. 같은 계좌의 정산이 현금·홀딩을 동시에 바꾸면 늦게 커밋한 쪽이
        // 계좌 행 전체를 덮어써 한쪽 변경이 사라진다. 주문은 잠근 뒤에 읽어야 같은 계좌 엔티티를 본다.
        accountRepository.findByIdWithLock(report.accountId())
                .orElseThrow(() -> new IllegalStateException("계좌를 찾을 수 없습니다. accountId=" + report.accountId()));
        Order order = orderRepository.findById(report.orderId())
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다. orderId=" + report.orderId()));

        if (report.orderMethod() == OrderMethod.BUY) {
            orderHoldService.releaseBuyHold(order);
        } else {
            orderHoldService.releaseSellHold(order, report.canceledQuantity());
        }

        if (report.canceledQuantity() > 0) {
            TransactionHandler.afterCommit(() -> orderSubscriptionService.unsubscribeOnCancel(order));
        }
        log.info("홀딩 해제 완료: type={} orderId={} canceledQuantity={}",
                report.type(), report.orderId(), report.canceledQuantity());
    }
}
