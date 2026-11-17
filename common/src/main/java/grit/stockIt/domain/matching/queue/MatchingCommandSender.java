package grit.stockIt.domain.matching.queue;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// 종목 코드를 키로 보내 한 종목의 체결, 취소가 한 파티션에 도착 순서대로 쌓이게 한다.
// 브로커 기록 확인까지 기다린다. 버퍼에만 넣고 돌아가면 그 사이 사라진 명령이 보이지 않는다.
@Slf4j
@Component
public class MatchingCommandSender {

    private final KafkaTemplate<String, MatchingCommand> kafkaTemplate;
    private final Duration ackTimeout;

    public MatchingCommandSender(KafkaTemplate<String, MatchingCommand> kafkaTemplate,
                                 @Value("${matching.queue.ack-timeout:1s}") Duration ackTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.ackTimeout = ackTimeout;
    }

    // 비어 있으면 기록됐는지 알 수 없다. 다시 보내면 새 위치가 붙어 워터마크로 거를 수 없으므로 호출자는 재전송하지 않는다.
    public Optional<RecordMetadata> send(MatchingCommand command) {
        try {
            return Optional.of(kafkaTemplate.send(MatchingTopics.COMMANDS, command.stockCode(), command)
                    .get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("명령 전송 중 인터럽트. command={}", command, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.error("명령 전송 실패. command={}", command, e);
        }
        return Optional.empty();
    }
}
