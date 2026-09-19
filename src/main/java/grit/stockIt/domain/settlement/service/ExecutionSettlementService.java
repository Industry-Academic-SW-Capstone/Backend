package grit.stockIt.domain.settlement.service;

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
import grit.stockIt.domain.settlement.entity.Settlement;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.domain.stock.entity.Stock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 체결 하나를 계좌에 반영한다. 체결 트랜잭션이 커밋된 뒤에 돌므로 종목 락 밖이고,
// 여기서 쓰는 왕복은 종목당 상한에 영향을 주지 않는다.
//
// 체결 직후(오케스트레이터)와 복구 배치 두 곳에서 같은 메서드로 들어온다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionSettlementService {

    private final ExecutionRepository executionRepository;
    private final SettlementRepository settlementRepository;
    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final OrderHoldRepository orderHoldRepository;
    private final ApplicationEventPublisher eventPublisher;

    // 체결 하나가 한 트랜잭션이다. 한 건이 실패해도 나머지 정산이 막히지 않고,
    // 실패한 건만 복구 배치가 다시 집는다.
    @Transactional(readOnly = true)
    public List<Long> findUnsettledExecutionIds(LocalDateTime cutoff, int limit) {
        return settlementRepository.findUnsettledExecutionIds(cutoff, limit);
    }

    @Transactional(readOnly = true)
    public long countUnsettled(LocalDateTime cutoff) {
        return settlementRepository.countUnsettled(cutoff);
    }

    @Transactional
    public void settle(Long executionId) {
        Execution execution = executionRepository.findById(executionId).orElse(null);
        if (execution == null) {
            log.warn("정산할 체결을 찾을 수 없습니다. executionId={}", executionId);
            return;
        }

        // 조회와 정산 사이에 다른 경로가 먼저 끝냈을 수 있다. 빠른 반환일 뿐이고
        // 실제 방어선은 아래 INSERT 의 유니크 제약이다.
        if (settlementRepository.existsByExecutionId(executionId)) {
            return;
        }

        Order order = execution.getOrder();
        Account account = accountRepository.findByIdWithLock(execution.getAccount().getAccountId())
                .orElseThrow(() -> new IllegalStateException(
                        "계좌를 찾을 수 없습니다. accountId=" + execution.getAccount().getAccountId()));

        AccountStock holding = accountStockRepository
                .findByAccountAndStock(account, order.getStock())
                .orElse(null);

        // 전량 매도면 아래에서 평단가가 0이 되므로 그 전에 담아둔다.
        BigDecimal currentAvgPrice = order.getOrderMethod() == OrderMethod.SELL && holding != null
                ? holding.getAveragePrice()
                : BigDecimal.ZERO;

        int quantity = execution.getQuantity();
        BigDecimal fillAmount = execution.getPrice().multiply(BigDecimal.valueOf(quantity));

        // 유니크 제약이 실제 방어선이다. 이미 정산된 체결이면 여기서 예외가 나고 트랜잭션이 통째로
        // 되돌아가므로, 현금이 두 번 빠지는 일이 구조적으로 없다.
        settlementRepository.save(Settlement.of(
                executionId,
                account.getAccountId(),
                order.getOrderMethod() == OrderMethod.BUY ? fillAmount.negate() : fillAmount,
                order.getOrderMethod() == OrderMethod.BUY ? quantity : -quantity));

        publishExecutionFilledEvent(execution, order, account);
        applyToAccount(order, execution.getPrice(), quantity, account, holding);

        // 체결이 이미 커밋됐으므로 주문의 잔여 수량이 곧 진실이다.
        if (order.getRemainingQuantity() <= 0) {
            releaseRemainingHold(order, account);
            publishTradeCompletionEvent(order, execution.getPrice(), account, currentAvgPrice);
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

    // 이 주문에 아직 정산되지 않은 다른 체결이 있으면 그 몫은 남긴다. 통째로 풀면 뒤늦게
    // 정산될 체결이 뺄 것을 잃는다.
    private void releaseRemainingHold(Order order, Account account) {
        orderHoldRepository.findById(order.getOrderId())
                .ifPresent(hold -> {
                    BigDecimal pending = settlementRepository.sumUnsettledFillAmount(order.getOrderId());
                    BigDecimal releasable = hold.getHoldAmount().subtract(pending).max(BigDecimal.ZERO)
                            .min(account.getHoldAmount());
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

    private void publishExecutionFilledEvent(Execution execution, Order order, Account account) {
        try {
            eventPublisher.publishEvent(new ExecutionFilledEvent(
                    execution.getExecutionId(),
                    order.getOrderId(),
                    account.getAccountId(),
                    account.getMember().getMemberId(),
                    account.getContest().getContestId(),
                    account.getContest().getContestName(),
                    order.getStock().getCode(),
                    execution.getStock().getName(),
                    execution.getPrice(),
                    execution.getQuantity(),
                    execution.getOrderMethod().name()
            ));
        } catch (Exception e) {
            log.error("체결 완료 이벤트 발행 실패: executionId={}", execution.getExecutionId(), e);
        }
    }
}
