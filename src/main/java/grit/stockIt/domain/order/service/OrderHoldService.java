package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
import grit.stockIt.domain.settlement.repository.SettlementRepository;
import grit.stockIt.global.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 주문 홀딩(현금/보유수량) 확보 및 해제
@Service
@RequiredArgsConstructor
@Transactional
public class OrderHoldService {

    private final OrderHoldRepository orderHoldRepository;
    private final AccountStockRepository accountStockRepository;
    // 매수 홀딩 잔액을 줄이는 주체가 정산이라, 얼마를 풀 수 있는지 알려면 정산 진행도를 봐야 한다.
    private final SettlementRepository settlementRepository;

    public void ensureSufficientCash(Account account, BigDecimal holdAmount) {
        if (account.getAvailableCash().compareTo(holdAmount) < 0) {
            throw new BadRequestException("주문 가능 현금이 부족합니다.");
        }
    }

    // 저장된 매수 주문에 대해 OrderHold를 생성한다. account.increaseHoldAmount는 save 전에
    // 오케스트레이터가 별도로 호출해야 한다(OrderHold가 저장된 order의 FK를 필요로 하므로 save 이후 호출).
    public void applyBuyHold(Order savedOrder, Account account, BigDecimal holdAmount) {
        OrderHold orderHold = OrderHold.create(savedOrder, account, holdAmount);
        orderHoldRepository.save(orderHold);
    }

    // 체결됐지만 정산이 아직인 몫은 남긴다. 매도 홀딩이 잔여 수량 기준으로 풀리는 것과 같은
    // 규약이다 — 전액을 풀면 뒤늦게 도착한 정산이 뺄 것을 잃고 영구 미정산으로 남는다.
    public void releaseBuyHold(Order order) {
        orderHoldRepository.findById(order.getOrderId()).ifPresent(hold -> {
            BigDecimal pending = settlementRepository.sumUnsettledFillAmount(order.getOrderId());
            BigDecimal releasable = hold.getHoldAmount().subtract(pending).max(BigDecimal.ZERO);
            if (releasable.signum() <= 0) {
                return;
            }
            Account account = order.getAccount();
            account.decreaseHoldAmount(releasable);
            hold.decreaseHoldAmount(releasable);
            orderHoldRepository.save(hold);
        });
    }

    public void applySellHold(Order order) {
        AccountStock accountStock = accountStockRepository.findByAccountAndStock(order.getAccount(), order.getStock())
                .orElseThrow(() -> new BadRequestException("보유 중인 종목이 없습니다."));
        accountStock.increaseHoldQuantity(order.getRemainingQuantity());
        accountStockRepository.save(accountStock);
    }

    public void releaseSellHold(Order order) {
        int releaseQuantity = order.getRemainingQuantity();
        if (releaseQuantity <= 0) {
            return;
        }
        accountStockRepository.findByAccountAndStock(order.getAccount(), order.getStock())
                .ifPresent(accountStock -> {
                    accountStock.decreaseHoldQuantity(releaseQuantity);
                    accountStockRepository.save(accountStock);
                });
    }
}
