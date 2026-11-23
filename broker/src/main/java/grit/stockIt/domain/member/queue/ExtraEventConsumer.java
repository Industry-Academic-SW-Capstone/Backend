package grit.stockIt.domain.member.queue;

import grit.stockIt.contract.ExtraEvent;
import grit.stockIt.contract.ExtraTopics;
import grit.stockIt.domain.account.service.RewardPaymentService;
import grit.stockIt.domain.member.service.MemberTierService;
import grit.stockIt.global.inbox.ProcessedEventWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 증권사 워커. 부가 서비스가 보낸 보상 지급 요청과 티어 변화를 받는다. 처리 기록과 계좌·회원 변경이 한 트랜잭션이라
// 같은 이벤트가 다시 와도 보상은 한 번만 지급되고, 실패하면 둘 다 롤백돼 다시 받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtraEventConsumer {

    static final String CONSUMER = "broker";

    private final RewardPaymentService rewardPaymentService;
    private final MemberTierService memberTierService;
    private final ProcessedEventWriter processedEventWriter;

    @KafkaListener(
            id = CONSUMER,
            topics = ExtraTopics.EVENTS,
            groupId = CONSUMER,
            concurrency = "${broker.extra-events.concurrency:2}",
            containerFactory = "extraEventListenerContainerFactory",
            properties = "spring.json.value.default.type=grit.stockIt.contract.ExtraEvent"
    )
    @Transactional
    public void consume(ExtraEvent event) {
        event.validate();
        if (!processedEventWriter.markProcessed(CONSUMER, event.eventId())) {
            log.info("이미 처리한 부가 이벤트라 건너뜁니다. type={} eventId={}", event.type(), event.eventId());
            return;
        }
        switch (event.type()) {
            case REWARD_GRANTED -> rewardPaymentService.pay(
                    event.eventId(), event.memberId(), event.rewardGranted().moneyAmount());
            case TIER_CHANGED -> memberTierService.updateTier(event.memberId(), event.tierChanged().tier());
        }
    }
}
