package grit.stockIt.domain.mission.queue;

import grit.stockIt.contract.MemberEvent;
import grit.stockIt.contract.MemberTopics;
import grit.stockIt.domain.mission.event.TradeCompletionEvent;
import grit.stockIt.domain.mission.service.MissionProgressService;
import grit.stockIt.domain.mission.service.MissionService;
import grit.stockIt.global.inbox.ProcessedEventWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 미션 워커. 증권사가 알린 회원 이벤트를 받아 미션 진행도를 바꾼다. 같은 이벤트는 한 번만 처리하고(처리 기록과 진행도 변경이
// 한 트랜잭션), 실패하면 던져 다시 받는다 — 예전 메모리 스레드 풀처럼 삼키면 그 진행도는 영영 빠진다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MissionEventConsumer {

    static final String CONSUMER = "mission";

    private final MissionService missionService;
    private final MissionProgressService missionProgressService;
    private final ProcessedEventWriter processedEventWriter;

    // 리스너 id·그룹은 지표 태그와 커밋된 offset 의 이름이다.
    @KafkaListener(
            id = CONSUMER,
            topics = MemberTopics.EVENTS,
            groupId = CONSUMER,
            concurrency = "${mission.queue.concurrency:2}",
            containerFactory = "memberEventListenerContainerFactory",
            properties = "spring.json.value.default.type=grit.stockIt.contract.MemberEvent"
    )
    @Transactional
    public void consume(MemberEvent event) {
        event.validate();
        if (!processedEventWriter.markProcessed(CONSUMER, event.eventId())) {
            log.info("이미 처리한 회원 이벤트라 건너뜁니다. type={} eventId={}", event.type(), event.eventId());
            return;
        }
        // switch 식이라 이벤트 종류가 늘면 여기서 컴파일이 깨진다.
        Runnable handler = switch (event.type()) {
            case TRADE_COMPLETED -> () -> missionProgressService.updateMissionProgress(toTradeCompletion(event));
            case MEMBER_REGISTERED -> () -> missionService.initializeMissionsForNewMember(event.memberId());
            case RANKER_ACHIEVED -> () -> missionProgressService.processRankerAchievement(List.of(event.memberId()));
            case STOCK_ANALYZED -> () -> missionService.handleReportView(event.memberId());
            case PORTFOLIO_ANALYZED -> () -> missionService.handlePortfolioAnalysis(event.memberId());
            case EXECUTION_FILLED -> () -> { };
        };
        handler.run();
    }

    private static TradeCompletionEvent toTradeCompletion(MemberEvent event) {
        MemberEvent.TradeCompleted trade = event.tradeCompleted();
        return new TradeCompletionEvent(event.memberId(), trade.accountId(), trade.defaultAccount(), trade.stockCode(),
                trade.orderMethod(), trade.filledQuantity(), trade.filledPrice(), trade.buyAveragePrice());
    }
}
