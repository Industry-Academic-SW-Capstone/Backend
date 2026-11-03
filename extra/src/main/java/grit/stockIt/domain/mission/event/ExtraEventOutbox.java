package grit.stockIt.domain.mission.event;

import grit.stockIt.contract.ExtraEvent;
import grit.stockIt.contract.ExtraTopics;
import grit.stockIt.global.outbox.OutboxRecord;
import grit.stockIt.global.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

// 부가가 증권사에 보상 지급을 요청하고 티어 변화를 알린다. 미션 트랜잭션의 아웃박스에 적어 미션 완료가 커밋된 것만 나가게 한다.
// 호출하는 쪽에 트랜잭션이 열려 있어야 한다.
@Component
@RequiredArgsConstructor
public class ExtraEventOutbox {

    private final OutboxWriter outboxWriter;

    public void rewardGranted(Long memberId, Long rewardId, long moneyAmount) {
        append(ExtraEvent.rewardGranted(memberId, new ExtraEvent.RewardGranted(rewardId, moneyAmount)));
    }

    public void tierChanged(Long memberId, String tier) {
        append(ExtraEvent.tierChanged(memberId, tier));
    }

    private void append(ExtraEvent event) {
        outboxWriter.append(List.of(new OutboxRecord(ExtraTopics.EVENTS, event.key(), event)));
    }
}
