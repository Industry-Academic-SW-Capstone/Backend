package grit.stockIt.domain.matching.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderEventPublisher {

    private static final String LIMIT_EVENT_QUEUE_KEY_PATTERN = "sim:limit:event:%s";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final LimitOrderMatchingService limitOrderMatchingService;

    @EventListener
    public void handleLimitOrderFill(LimitOrderFillEventMessage message) {
        LimitOrderFillEvent event = new LimitOrderFillEvent(
                message.eventId(),
                message.orderMethod(),
                message.price(),
                message.quantity(),
                message.eventTimestamp()
        );
        publish(message.stockCode(), event);
    }

    private void publish(String stockCode, LimitOrderFillEvent event) {
        try {
            String queueKey = queueKey(stockCode);
            String payload = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(queueKey, payload);
        } catch (Exception e) {
            log.error("지정가 이벤트 직렬화 실패. stockCode={} event={}", stockCode, event, e);
            return;
        }

        try {
            limitOrderMatchingService.consumeNextEvent(stockCode);
        } catch (Exception e) {
            log.error("지정가 이벤트 처리 실패. stockCode={} event={}", stockCode, event, e);
        }
    }

    private String queueKey(String stockCode) {
        return LIMIT_EVENT_QUEUE_KEY_PATTERN.formatted(stockCode);
    }
}

