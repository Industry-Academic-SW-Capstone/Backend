package grit.stockIt.global.websocket.listener;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.contract.ExecutionReportType;
import grit.stockIt.global.websocket.manager.OrderSubscriptionCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.List;

// 시세 워커. 거래소 결과 통보로 오더북에 열린 주문을 따라가 그 종목의 실시간 구독을 유지한다.
// 열린 주문은 메모리에만 있어, 기동하면 거래소에 열린 주문을 물어 되살린 뒤에 통보를 받기 시작한다 —
// 먼저 받으면 되살리기 전에 처리한 "닫힘"이 뒤늦은 "열림"에 덮여 구독이 영영 남을 수 있다.
@Slf4j
@Component
public class OpenOrderReportConsumer {

    static final String CONSUMER = "market";

    private final OrderSubscriptionCoordinator orderSubscriptionCoordinator;
    private final ExchangeQueryClient exchangeQueryClient;
    private final KafkaListenerEndpointRegistry listenerRegistry;
    // 리스너 자동 시작을 끈 환경(테스트)에서는 되살리지도 시작하지도 않는다.
    private final boolean autoStartup;

    public OpenOrderReportConsumer(OrderSubscriptionCoordinator orderSubscriptionCoordinator,
                                   ExchangeQueryClient exchangeQueryClient,
                                   KafkaListenerEndpointRegistry listenerRegistry,
                                   @Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
        this.orderSubscriptionCoordinator = orderSubscriptionCoordinator;
        this.exchangeQueryClient = exchangeQueryClient;
        this.listenerRegistry = listenerRegistry;
        this.autoStartup = autoStartup;
    }

    // 한 스레드로 받는다. 열린 주문 등록과 구독 판단이 종목마다 겹치지 않게 한다 — 메모리 연산뿐이라 충분히 빠르다.
    // 실패 처리는 정산과 같다(정해진 횟수 재시도 후 DLT).
    @KafkaListener(
            id = CONSUMER,
            topics = ExchangeTopics.REPORTS,
            groupId = CONSUMER,
            concurrency = "1",
            autoStartup = "false",
            containerFactory = "settlementListenerContainerFactory",
            // 처음 뜨는 그룹은 보관된 통보를 처음부터 읽지 않는다 — 현재 상태는 기동 시 복구가 채우고, 옛 통보를 다시 읽으면
            // 이미 끝난 주문들의 종목을 잠깐씩 구독했다 푼다(KIS 구독 수 제한).
            properties = {
                    "spring.json.value.default.type=grit.stockIt.contract.ExecutionReport",
                    "auto.offset.reset=latest"
            }
    )
    public void consume(ExecutionReport report) {
        report.validate();
        if (report.type() == ExecutionReportType.ACCEPTED) {
            orderSubscriptionCoordinator.orderOpened(report.stockCode(), report.orderId());
        } else if (closesOrder(report)) {
            orderSubscriptionCoordinator.orderClosed(report.stockCode(), report.orderId());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreThenStart() {
        if (!autoStartup) {
            return;
        }
        restore();
        MessageListenerContainer container = listenerRegistry.getListenerContainer(CONSUMER);
        if (container != null) {
            container.start();
        }
    }

    void restore() {
        List<ExchangeQueryClient.OpenOrder> openOrders = exchangeQueryClient.openOrders();
        openOrders.forEach(order -> orderSubscriptionCoordinator.orderOpened(order.stockCode(), order.orderId()));
        log.info("열린 주문 {}건으로 실시간 구독을 되살렸습니다.", openOrders.size());
    }

    private static boolean closesOrder(ExecutionReport report) {
        return (report.type() == ExecutionReportType.FILL && report.leavesQuantity() == 0) || report.endsOrder();
    }
}
