package grit.stockIt.domain.member.event;

import grit.stockIt.contract.MemberEvent;
import grit.stockIt.contract.MemberEventType;
import grit.stockIt.contract.MemberTopics;
import grit.stockIt.global.outbox.OutboxRecord;
import grit.stockIt.global.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

// 회원에게 일어난 일을 부가 서비스(미션·알림)에 알린다. 정산·가입·랭킹처럼 트랜잭션 안의 일은 아웃박스에 적어
// 커밋된 것만 나가게 한다. 호출하는 쪽에 트랜잭션이 열려 있어야 한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberEventOutbox {

    private final OutboxWriter outboxWriter;
    private final KafkaTemplate<String, MemberEvent> kafkaTemplate;

    public void append(MemberEvent event) {
        outboxWriter.append(List.of(toRecord(event)));
    }

    // 랭커는 회원마다 한 이벤트다 — 키가 회원이라 회원별 순서를 지킨다.
    public void rankersAchieved(List<Long> memberIds) {
        outboxWriter.append(memberIds.stream()
                .map(memberId -> toRecord(MemberEvent.of(MemberEventType.RANKER_ACHIEVED, memberId)))
                .toList());
    }

    // AI 분석 완료는 트랜잭션이 없는 비동기 응답 스레드에서 난다. 아웃박스 대신 바로 보내고, 잃어도 미션 진행도 한 번이라 감수한다.
    public void sendNow(MemberEvent event) {
        try {
            kafkaTemplate.send(MemberTopics.EVENTS, event.key(), event)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.warn("회원 이벤트 전송 실패. type={} key={}", event.type(), event.key(), exception);
                        }
                    });
        } catch (RuntimeException e) {
            log.warn("회원 이벤트 전송 실패. type={} key={}", event.type(), event.key(), e);
        }
    }

    private static OutboxRecord toRecord(MemberEvent event) {
        return new OutboxRecord(MemberTopics.EVENTS, event.key(), event);
    }
}
