package grit.stockIt.domain.order.service;

import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.order.entity.Order;
import grit.stockIt.domain.stock.service.StockDetailService;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.UntradeableStockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

// 주문 pricing + tradeability guard
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderPricingService {

    private final RedisMarketDataRepository redisMarketDataRepository;
    private final StockDetailService stockDetailService;

    @Value("${order.market.hold-buffer-rate:0.05}")
    private BigDecimal marketHoldBufferRate;

    public BigDecimal calculateHoldAmount(Order order) {
        return order.getPrice().multiply(BigDecimal.valueOf(order.getRemainingQuantity()));
    }

    // 시장가 주문 홀딩 금액 계산
    public BigDecimal calculateMarketHoldAmount(String stockCode, int quantity) {
        // 1. Redis 캐시에서 먼저 조회
        BigDecimal lastPrice = redisMarketDataRepository.getLastPrice(stockCode)
                .orElseGet(() -> {
                    // 2. 캐시에 없으면 KIS API 호출
                    log.info("캐시에 현재가가 없어 KIS API 호출: stockCode={}", stockCode);
                    try {
                        BigDecimal price = stockDetailService.getCurrentPrice(stockCode)
                                .block(java.time.Duration.ofSeconds(5));
                        if (price == null || price.signum() <= 0) {
                            throw new BadRequestException("KIS API에서 현재가를 가져올 수 없습니다.");
                        }
                        // KIS API 결과는 StockDetailService에서 이미 Redis에 저장됨
                        return price;
                    } catch (Exception e) {
                        log.error("KIS API 현재가 조회 실패: stockCode={}", stockCode, e);
                        throw new BadRequestException("최근 체결가 정보를 찾을 수 없습니다.");
                    }
                });

        if (lastPrice.signum() <= 0) {
            throw new BadRequestException("최근 체결가가 유효하지 않습니다.");
        }
        BigDecimal bufferRate = Optional.ofNullable(marketHoldBufferRate).orElse(BigDecimal.valueOf(0.05));
        if (bufferRate.signum() < 0) {
            bufferRate = BigDecimal.ZERO;
        }
        BigDecimal bufferFactor = BigDecimal.ONE.add(bufferRate);
        BigDecimal baseAmount = lastPrice.multiply(BigDecimal.valueOf(quantity));
        return baseAmount.multiply(bufferFactor).setScale(2, RoundingMode.UP);
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
