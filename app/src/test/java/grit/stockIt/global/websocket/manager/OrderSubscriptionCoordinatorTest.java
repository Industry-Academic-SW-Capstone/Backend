package grit.stockIt.global.websocket.manager;

import grit.stockIt.global.websocket.client.KisWebSocketClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("열린 주문에 따른 실시간 구독 — 주문 번호로 세어 같은 통보가 두 번 와도 결과가 같다")
class OrderSubscriptionCoordinatorTest {

    private static final String STOCK = "005930";

    private final WebSocketSubscriptionManager subscriptionManager = new WebSocketSubscriptionManager();
    private final KisWebSocketClient kisWebSocketClient = mock(KisWebSocketClient.class);
    private final OrderSubscriptionCoordinator coordinator =
            new OrderSubscriptionCoordinator(subscriptionManager, kisWebSocketClient);

    @Test
    @DisplayName("같은 주문이 두 번 열려도(재전달) 한 건으로 세고 구독은 한 번만 연다")
    void sameOrderOpenedTwice_countsOnce() {
        coordinator.orderOpened(STOCK, 1L);
        coordinator.orderOpened(STOCK, 1L);

        assertThat(subscriptionManager.getOrderReferenceCount(STOCK)).isEqualTo(1);
        verify(kisWebSocketClient, times(1)).subscribe(STOCK);
    }

    @Test
    @DisplayName("같은 주문이 두 번 닫혀도 다른 주문의 구독을 닫지 않는다")
    void sameOrderClosedTwice_keepsOtherOrder() {
        coordinator.orderOpened(STOCK, 1L);
        coordinator.orderOpened(STOCK, 2L);

        coordinator.orderClosed(STOCK, 1L);
        coordinator.orderClosed(STOCK, 1L);

        assertThat(subscriptionManager.getOrderReferenceCount(STOCK)).isEqualTo(1);
        verify(kisWebSocketClient, never()).unsubscribe(STOCK);
    }

    @Test
    @DisplayName("마지막 열린 주문이 닫히면 구독을 닫는다")
    void lastOrderClosed_unsubscribes() {
        coordinator.orderOpened(STOCK, 1L);

        coordinator.orderClosed(STOCK, 1L);

        assertThat(subscriptionManager.getOrderReferenceCount(STOCK)).isZero();
        verify(kisWebSocketClient).unsubscribe(STOCK);
    }

    @Test
    @DisplayName("보는 사람이 있으면 주문이 모두 닫혀도 구독을 유지한다")
    void viewerPresent_keepsSubscription() {
        subscriptionManager.incrementSubscribers(STOCK);
        coordinator.orderOpened(STOCK, 1L);

        coordinator.orderClosed(STOCK, 1L);

        verify(kisWebSocketClient, never()).subscribe(STOCK);
        verify(kisWebSocketClient, never()).unsubscribe(STOCK);
    }

    @Test
    @DisplayName("열린 적 없는 주문이 닫히면(거부 등) 아무것도 하지 않는다")
    void unknownOrderClosed_doesNothing() {
        coordinator.orderClosed(STOCK, 99L);

        verify(kisWebSocketClient, never()).unsubscribe(STOCK);
    }
}
