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
import java.util.Optional;

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

    public void releaseBuyHold(Order order) {
        Optional<OrderHold> holdOpt = orderHoldRepository.findById(order.getOrderId());
        holdOpt.ifPresent(hold -> {
            Account account = order.getAccount();
            BigDecimal holdAmount = hold.getHoldAmount();
            if (holdAmount.signum() > 0) {
                account.decreaseHoldAmount(holdAmount);
            }
            hold.release();
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
