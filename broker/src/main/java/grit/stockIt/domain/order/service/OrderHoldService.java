package grit.stockIt.domain.order.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.order.entity.OrderHold;
import grit.stockIt.domain.order.repository.OrderHoldRepository;
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

    // 남은 홀딩을 모두 푼다. 거래소 통보는 주문마다 순서대로 오므로, 주문을 끝내는 통보가 왔을 때
    // 그 주문의 체결은 이미 모두 정산돼 홀딩에서 빠져 있다.
    public void releaseBuyHold(Order order) {
        orderHoldRepository.findById(order.getOrderId()).ifPresent(hold -> {
            BigDecimal releasable = hold.getHoldAmount();
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

    // 풀 수량은 거래소가 취소 시점의 남은 수량으로 알려 준다.
    public void releaseSellHold(Order order, int releaseQuantity) {
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
