package grit.stockIt.domain.matching.event;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

// KIS 피드의 체결 틱을 체결 입구로 넘긴다.
@Component
@RequiredArgsConstructor
public class LimitOrderFillEventListener {

    private final FillCommandPublisher fillCommandPublisher;

    @EventListener
    public void handle(LimitOrderFillEventMessage message) {
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                message.eventId(),
                message.orderMethod(),
                message.price(),
                message.quantity(),
                message.eventTimestamp()
        );
        fillCommandPublisher.publish(message.stockCode(), event);
    }
}
