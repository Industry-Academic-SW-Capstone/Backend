package grit.stockIt.domain.settlement.service;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.execution.repository.ExecutionRepository;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.order.repository.OrderRepository;
import grit.stockIt.domain.settlement.entity.Settlement;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.domain.stock.entity.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 체결 하나를 계좌에 반영한다. 체결 트랜잭션이 커밋된 뒤 정산 워커가 돌므로 종목의 체결 줄 밖이고,
// 여기서 쓰는 왕복은 종목당 상한에 영향을 주지 않는다. 체결 내용은 결과 통보에서 읽고 거래소 테이블은 읽지 않는다.
//
// 체결 내역(execution)도 여기서 함께 적는다 — 체결 내역과 정산은 같은 트랜잭션이라 "체결은 있는데 정산이 없는" 상태가 없다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionSettlementService {

    private final ExecutionRepository executionRepository;
    private final SettlementRepository settlementRepository;
    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final OrderRepository orderRepository;
    private final OrderHoldRepository orderHoldRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void settle(ExecutionReport report) {
        Long executionId = report.executionId();

        // 조회와 정산 사이에 다른 경로가 먼저 끝냈을 수 있다. 빠른 반환일 뿐이고
        // 실제 방어선은 아래 INSERT 의 유니크 제약이다.
        if (settlementRepository.existsByExecutionId(executionId)) {
            return;
        }

        // 계좌를 먼저 잠그고 주문을 읽는다. 주문을 먼저 읽으면 같은 주문의 다른 체결 정산이 그 사이 커밋한 체결 수량을
        // 보지 못한 채 덮어쓴다. 같은 계좌의 정산·홀딩 해제는 이 잠금으로 한 줄로 선다.
        Account account = accountRepository.findByIdWithLock(report.accountId())
                .orElseThrow(() -> new IllegalStateException(
                        "계좌를 찾을 수 없습니다. accountId=" + report.accountId()));
        Order order = orderRepository.findById(report.orderId()).orElse(null);
        if (order == null) {
            log.warn("정산할 체결의 주문을 찾을 수 없습니다. executionId={} orderId={}", executionId, report.orderId());
            return;
        }

        AccountStock holding = accountStockRepository
                .findByAccountAndStock(account, order.getStock())
                .orElse(null);

        // 전량 매도면 아래에서 평단가가 0이 되므로 그 전에 담아둔다.
        BigDecimal currentAvgPrice = order.getOrderMethod() == OrderMethod.SELL && holding != null
                ? holding.getAveragePrice()
                : BigDecimal.ZERO;

        int quantity = report.quantity();
        BigDecimal fillAmount = report.price().multiply(BigDecimal.valueOf(quantity));

        // settlement 가 execution 을 외래키로 가리키므로 먼저 내보낸다(settlement 는 IDENTITY 라 저장 즉시 INSERT 된다).
        executionRepository.saveAndFlush(Execution.of(executionId, order, report.price(), quantity));
        order.applyReportedFill(report.cumQuantity());

        // 유니크 제약이 실제 방어선이다. 이미 정산된 체결이면 여기서 예외가 나고 트랜잭션이 통째로
        // 되돌아가므로, 현금이 두 번 빠지는 일이 구조적으로 없다.
        settlementRepository.save(Settlement.of(
                executionId,
                account.getAccountId(),
                order.getOrderMethod() == OrderMethod.BUY ? fillAmount.negate() : fillAmount,
                order.getOrderMethod() == OrderMethod.BUY ? quantity : -quantity));

        publishExecutionFilledEvent(report, order, account);
        applyToAccount(order, report.price(), quantity, account, holding);

        // 거래소가 이 체결 뒤 남은 수량을 알려 준다. 0 이면 이 주문의 마지막 체결이고, 통보는 순서대로 오므로
        // 앞선 체결은 모두 정산된 뒤다.
        if (report.leavesQuantity() == 0) {
            releaseRemainingHold(order, account);
            publishTradeCompletionEvent(order, report.price(), account, currentAvgPrice);
        }
    }

    private void applyToAccount(Order order, BigDecimal price, int fillQuantity,
                                Account account, AccountStock holding) {
        BigDecimal fillAmount = price.multiply(BigDecimal.valueOf(fillQuantity));

        if (order.getOrderMethod() == OrderMethod.BUY) {
            account.decreaseCash(fillAmount);
            orderHoldRepository.findById(order.getOrderId())
                    .ifPresentOrElse(
                            hold -> deductHold(account, hold, fillAmount),
                            () -> log.warn("OrderHold를 찾을 수 없습니다. orderId={}", order.getOrderId())
                    );
            increaseHolding(account, order.getStock(), fillQuantity, price, holding);
            return;
        }

        if (order.getOrderMethod() == OrderMethod.SELL) {
            account.increaseCash(fillAmount);
            if (holding == null) {
                log.warn("AccountStock을 찾을 수 없습니다. orderId={} accountId={} stockCode={}",
                        order.getOrderId(), account.getAccountId(), order.getStock().getCode());
                return;
            }
            holding.decreaseHoldQuantity(fillQuantity);
            holding.decreaseQuantity(fillQuantity);
        }
    }

    // 취소·만료가 먼저 홀딩을 풀었으면 뺄 것이 남아 있지 않다. 현금은 위에서 이미 차감했으므로
    // 원장은 맞는다. 여기서 예외를 던지면 이 체결이 영구 미정산으로 남는다.
    private void deductHold(Account account, OrderHold hold, BigDecimal fillAmount) {
        BigDecimal deductible = fillAmount.min(hold.getHoldAmount()).min(account.getHoldAmount());
        if (deductible.signum() <= 0) {
            log.warn("정산 시점에 남은 홀딩이 없습니다. orderId={} fillAmount={}", hold.getOrderId(), fillAmount);
            return;
        }
        account.decreaseHoldAmount(deductible);
        hold.decreaseHoldAmount(deductible);
        if (deductible.compareTo(fillAmount) < 0) {
            log.warn("홀딩이 체결 금액보다 적어 남은 만큼만 차감했습니다. orderId={} fillAmount={} 차감={}",
                    hold.getOrderId(), fillAmount, deductible);
        }
    }

    private void increaseHolding(Account account, Stock stock, int fillQuantity,
                                 BigDecimal price, AccountStock holding) {
        if (holding == null) {
            accountStockRepository.save(AccountStock.create(account, stock, fillQuantity, price));
            return;
        }
        holding.increaseQuantity(fillQuantity, price);
    }

    // 마지막 체결까지 정산했으므로 남은 홀딩(지정가보다 싸게 체결된 차액 등)을 모두 푼다.
    private void releaseRemainingHold(Order order, Account account) {
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal releasable = hold.getHoldAmount().min(account.getHoldAmount());
                    if (releasable.signum() > 0) {
                        account.decreaseHoldAmount(releasable);
                        hold.decreaseHoldAmount(releasable);
                    }
                });
    }

    private void publishTradeCompletionEvent(Order order, BigDecimal price,
                                             Account account, BigDecimal currentAvgPrice) {
        TradeCompletionEvent missionEvent = new TradeCompletionEvent(
                account.getMember().getMemberId(),
                account.getAccountId(),
                order.getStock().getCode(),
                order.getOrderMethod(),
                order.getQuantity(),
                price,
                null,
                null,
                0,
                currentAvgPrice
        );
        eventPublisher.publishEvent(missionEvent);
    }

    private void publishExecutionFilledEvent(ExecutionReport report, Order order, Account account) {
        try {
            eventPublisher.publishEvent(new ExecutionFilledEvent(
                    report.executionId(),
                    order.getOrderId(),
                    account.getAccountId(),
                    account.getMember().getMemberId(),
                    account.getContest().getContestId(),
                    account.getContest().getContestName(),
                    order.getStock().getCode(),
                    order.getStock().getName(),
                    report.price(),
                    report.quantity(),
                    report.orderMethod().name()
            ));
        } catch (Exception e) {
            log.error("체결 완료 이벤트 발행 실패: executionId={}", report.executionId(), e);
        }
    }
}
