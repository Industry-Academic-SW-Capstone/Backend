package grit.stockIt.domain.matching.queue;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// 체결 워커. 파티션 하나를 스레드 하나가 맡아 그 안의 명령(주문, 체결, 취소, 만료)을 도착 순서대로 처리한다.
// 체결 결과 통보는 체결 트랜잭션이 아웃박스에 적고, 아웃박스 릴레이가 exchange.reports 로 보낸다.
@Component
@RequiredArgsConstructor
public class MatchingCommandConsumer {

    private final MatchingCommandHandler handler;

    // 리스너 id·그룹은 지표 태그와 커밋된 offset 의 이름이다. 바꾸면 그룹이 처음부터 다시 읽는다.
    @KafkaListener(
            id = "matching-fill",
            topics = MatchingTopics.COMMANDS,
            groupId = "matching-fill",
            concurrency = "${matching.queue.fill-concurrency:24}",
            properties = "spring.json.value.default.type=grit.stockIt.domain.matching.queue.MatchingCommand"
    )
    public void consume(ConsumerRecord<String, MatchingCommand> record) {
        CommandPosition position = new CommandPosition(record.topic(), record.partition(), record.offset());
        handler.recordMetrics(handler.handle(record.value(), position));
    }
}
