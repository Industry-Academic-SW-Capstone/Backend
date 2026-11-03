package grit.stockIt.domain.notification.queue;

import grit.stockIt.contract.MemberEvent;
import grit.stockIt.contract.MemberEventType;
import grit.stockIt.contract.MemberTopics;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.service.ExecutionNotificationService;
import grit.stockIt.global.inbox.ProcessedEventWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 알림 워커. 증권사가 알린 체결을 받아 알림 내역을 남기고 푸시를 보낸다. 미션과 다른 소비자 그룹이라 서로 기다리지 않는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    static final String CONSUMER = "notification";

    private final ExecutionNotificationService executionNotificationService;
    private final ProcessedEventWriter processedEventWriter;

    @KafkaListener(
            id = CONSUMER,
            topics = MemberTopics.EVENTS,
            groupId = CONSUMER,
            concurrency = "${notification.queue.concurrency:2}",
            containerFactory = "memberEventListenerContainerFactory",
            properties = "spring.json.value.default.type=grit.stockIt.contract.MemberEvent"
    )
    @Transactional
    public void consume(MemberEvent event) {
        event.validate();
        if (event.type() != MemberEventType.EXECUTION_FILLED) {
            return;
        }
        if (!processedEventWriter.markProcessed(CONSUMER, event.eventId())) {
            log.info("이미 처리한 체결 알림이라 건너뜁니다. eventId={}", event.eventId());
            return;
        }
        executionNotificationService.handleExecutionFilledEvent(toExecutionFilled(event));
    }

    private static ExecutionFilledEvent toExecutionFilled(MemberEvent event) {
        MemberEvent.ExecutionFilled filled = event.executionFilled();
        return new ExecutionFilledEvent(filled.executionId(), filled.orderId(), filled.accountId(), event.memberId(),
                filled.contestId(), filled.contestName(), filled.stockCode(), filled.stockName(), filled.price(),
                filled.quantity(), filled.orderMethod());
    }
}
