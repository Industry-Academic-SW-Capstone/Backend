package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.repository.RedisMarketDataRepository;
import grit.stockIt.domain.matching.service.FillCommandPublisher;
import grit.stockIt.domain.matching.service.FillDispatchResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class KafkaFillCommandPublisher implements FillCommandPublisher {

    private final KafkaTemplate<String, MatchingCommand> kafkaTemplate;
    private final Duration ackTimeout;
    private final MatchingQueueMetrics metrics;
    private final RedisMarketDataRepository redisMarketDataRepository;

    public KafkaFillCommandPublisher(KafkaTemplate<String, MatchingCommand> kafkaTemplate,
                                     @Value("${matching.queue.ack-timeout:1s}") Duration ackTimeout,
                                     MatchingQueueMetrics metrics,
                                     RedisMarketDataRepository redisMarketDataRepository) {
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeout = ackTimeout;
        this.metrics = metrics;
        this.redisMarketDataRepository = redisMarketDataRepository;
    }

    // 브로커 기록 확인까지 기다린다. 버퍼에만 넣고 돌아가면 그 사이 사라진 이벤트가 보이지 않는다.
    // 현재가는 체결 처리와 무관한 시세라 틱이 도착한 지금 갱신한다. 워커에서 하면 큐가 밀린 만큼 시세도 늦게 보이고,
    // 종목을 한 줄로 처리하는 워커의 왕복이 하나 는다.
    @Override
    public FillDispatchResult publish(String stockCode, LimitOrderFillEvent event) {
        redisMarketDataRepository.updateLastPrice(stockCode, event.price());
        MatchingCommand command = MatchingCommand.fill(stockCode, event, System.currentTimeMillis());
        try {
            RecordMetadata metadata = kafkaTemplate.send(MatchingTopics.COMMANDS, stockCode, command)
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            metrics.enqueued(true);
            return new FillDispatchResult.Queued(metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("체결 명령 전송 중 인터럽트. stockCode={} eventId={}", stockCode, event.eventId(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.error("체결 명령 전송 실패. stockCode={} eventId={}", stockCode, event.eventId(), e);
        }
        metrics.enqueued(false);
        return new FillDispatchResult.Failed();
    }
}
