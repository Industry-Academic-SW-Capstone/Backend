package grit.stockIt.domain.matching.service;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("거래소 동기 조회 — 시세 기동 복구용 열린 주문 (통합 테스트)")
class LocalExchangeQueryClientIntegrationTest extends IntegrationTestSupport {

    @Autowired private ExchangeQueryClient exchangeQueryClient;
    @Autowired private ExchangeOrderRepository exchangeOrderRepository;

    @Test
    @DisplayName("오더북에 열린 주문만 주문 번호·종목으로 돌려준다")
    void openOrders_returnsOnlyOpenOrders() {
        long base = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        exchangeOrderRepository.save(order(base, "005930", 10));
        ExchangeOrder filled = order(base + 1, "000660", 10);
        filled.applyFill(10);
        exchangeOrderRepository.save(filled);

        List<ExchangeQueryClient.OpenOrder> openOrders = exchangeQueryClient.openOrders();

        assertThat(openOrders).contains(new ExchangeQueryClient.OpenOrder(base, "005930"));
        assertThat(openOrders).extracting(ExchangeQueryClient.OpenOrder::orderId).doesNotContain(base + 1);
    }

    private static ExchangeOrder order(long orderId, String stockCode, int quantity) {
        return ExchangeOrder.accept(orderId, 1L, stockCode, OrderMethod.BUY, OrderType.LIMIT,
                new BigDecimal("70000"), quantity);
    }
}
