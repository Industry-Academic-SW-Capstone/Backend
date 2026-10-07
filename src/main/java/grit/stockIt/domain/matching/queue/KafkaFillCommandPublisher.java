package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@ConditionalOnProperty(name = "matching.queue.enabled", havingValue = "true")
public class KafkaFillCommandPublisher implements FillCommandPublisher {

    private final KafkaTemplate<String, MatchingCommand> kafkaTemplate;
    private final Duration ackTimeout;

    public KafkaFillCommandPublisher(KafkaTemplate<String, MatchingCommand> kafkaTemplate,
                                     @Value("${matching.queue.ack-timeout:1s}") Duration ackTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeout = ackTimeout;
    }

    // 브로커 기록 확인까지 기다린다. 버퍼에만 넣고 돌아가면 그 사이 사라진 이벤트가 보이지 않는다.
    @Override
    public FillDispatchResult publish(String stockCode, LimitOrderFillEvent event) {
        MatchingCommand command = MatchingCommand.fill(stockCode, event, System.currentTimeMillis());
        try {
            RecordMetadata metadata = kafkaTemplate.send(MatchingTopics.COMMANDS, stockCode, command)
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            return new FillDispatchResult.Queued(metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("체결 명령 전송 중 인터럽트. stockCode={} eventId={}", stockCode, event.eventId(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.error("체결 명령 전송 실패. stockCode={} eventId={}", stockCode, event.eventId(), e);
        }
        return new FillDispatchResult.Failed();
    }
}
