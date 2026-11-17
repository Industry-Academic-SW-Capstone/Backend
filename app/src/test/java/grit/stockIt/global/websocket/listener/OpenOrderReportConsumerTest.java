package grit.stockIt.global.websocket.listener;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.contract.ExecutionReportType;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("시세 워커 — 거래소 통보로 열린 주문을 따라간다")
class OpenOrderReportConsumerTest {

    private static final String STOCK = "005930";
    private static final Long ORDER_ID = 7L;

    private final OrderSubscriptionCoordinator coordinator = mock(OrderSubscriptionCoordinator.class);
    private final ExchangeQueryClient exchangeQueryClient = mock(ExchangeQueryClient.class);
    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final OpenOrderReportConsumer consumer =
            new OpenOrderReportConsumer(coordinator, exchangeQueryClient, registry, true);

    @Test
    @DisplayName("접수되면 열린 주문으로 등록한다")
    void accepted_opensOrder() {
        consumer.consume(report(ExecutionReportType.ACCEPTED));

        verify(coordinator).orderOpened(STOCK, ORDER_ID);
    }

    @Test
    @DisplayName("일부 체결은 주문이 아직 열려 있어 아무것도 하지 않는다")
    void partialFill_keepsOrderOpen() {
        consumer.consume(fill(3));

        verifyNoInteractions(coordinator);
    }

    @Test
    @DisplayName("남은 수량 없이 체결되면 주문을 닫는다")
    void finalFill_closesOrder() {
        consumer.consume(fill(0));

        verify(coordinator).orderClosed(STOCK, ORDER_ID);
    }

    @Test
    @DisplayName("취소·만료·거부는 주문을 닫는다")
    void cancelExpireReject_closeOrder() {
        consumer.consume(report(ExecutionReportType.CANCELED));
        consumer.consume(report(ExecutionReportType.EXPIRED));
        consumer.consume(report(ExecutionReportType.REJECTED));

        verify(coordinator, times(3)).orderClosed(STOCK, ORDER_ID);
        verify(coordinator, never()).orderOpened(any(), any());
    }

    @Test
    @DisplayName("기동하면 거래소의 열린 주문으로 되살린 뒤에 통보를 받기 시작한다")
    void ready_restoresBeforeStartingListener() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(OpenOrderReportConsumer.CONSUMER)).thenReturn(container);
        when(exchangeQueryClient.openOrders()).thenReturn(List.of(
                new ExchangeQueryClient.OpenOrder(1L, STOCK), new ExchangeQueryClient.OpenOrder(2L, "000660")));

        consumer.restoreThenStart();

        InOrder inOrder = inOrder(coordinator, container);
        inOrder.verify(coordinator).orderOpened(STOCK, 1L);
        inOrder.verify(coordinator).orderOpened("000660", 2L);
        inOrder.verify(container).start();
    }

    @Test
    @DisplayName("리스너 자동 시작을 끈 환경에서는 되살리지도 시작하지도 않는다")
    void autoStartupOff_doesNothing() {
        OpenOrderReportConsumer off = new OpenOrderReportConsumer(coordinator, exchangeQueryClient, registry, false);

        off.restoreThenStart();

        verifyNoInteractions(exchangeQueryClient, registry, coordinator);
    }

    private static ExecutionReport report(ExecutionReportType type) {
        return ExecutionReport.of(type, ORDER_ID, 1L, STOCK, OrderMethod.BUY, 5, System.currentTimeMillis());
    }

    private static ExecutionReport fill(int leavesQuantity) {
        return ExecutionReport.fill(100L, ORDER_ID, 1L, STOCK, OrderMethod.BUY, new BigDecimal("70000"), 2,
                2, leavesQuantity, System.currentTimeMillis());
    }
}
