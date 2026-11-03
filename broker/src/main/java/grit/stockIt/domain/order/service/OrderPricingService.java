package grit.stockIt.domain.order.service;

import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.UntradeableStockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

// 주문 pricing + tradeability guard
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderPricingService {

    private final StockDetailService stockDetailService;

    public BigDecimal calculateHoldAmount(Order order) {
        return order.getPrice().multiply(BigDecimal.valueOf(order.getRemainingQuantity()));
    }

    // 시장가는 체결가 상한이 없어 주문 시점에 체결 금액을 알 수 없다. 가격제한폭 상한가로 묶으면
    // 체결가가 그 위로 갈 수 없으므로 지정가와 같은 "홀딩 >= 체결금액" 보장이 선다.
    public BigDecimal calculateMarketHoldAmount(String stockCode, int quantity) {
        BigDecimal upperLimitPrice;
        try {
            upperLimitPrice = stockDetailService.getUpperLimitPrice(stockCode)
                    .block(java.time.Duration.ofSeconds(5));
        } catch (Exception e) {
            log.error("상한가 조회 실패: stockCode={}", stockCode, e);
            throw new BadRequestException("상한가 정보를 찾을 수 없습니다.");
        }

        if (upperLimitPrice == null || upperLimitPrice.signum() <= 0) {
            throw new BadRequestException("상한가가 유효하지 않습니다.");
        }
        // 올림한다 — 홀딩은 남는 쪽보다 모자라는 쪽이 위험하다.
        return upperLimitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.UP);
    }

    // 거래 가능 종목인지 검증
    public void validateStockTradeable(String stockCode) {
        try {
            var stockDetail = stockDetailService.getStockDetail(stockCode)
                    .block(java.time.Duration.ofSeconds(5));

            if (stockDetail == null || !Boolean.TRUE.equals(stockDetail.tradeable())) {
                String reason = stockDetail != null && stockDetail.untradeableReason() != null
                        ? stockDetail.untradeableReason()
                        : "이 종목은 AI 분석이 불가능하여 거래가 제한됩니다.";
                throw new UntradeableStockException(reason);
            }
        } catch (UntradeableStockException e) {
            throw e;
        } catch (Exception e) {
            log.error("종목 거래 가능 여부 확인 실패: stockCode={}", stockCode, e);
            throw new UntradeableStockException("종목 거래 가능 여부를 확인할 수 없습니다.");
        }
    }
}
