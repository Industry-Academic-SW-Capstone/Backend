package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.member.event.MemberRegisteredEvent;
import grit.stockIt.domain.mission.event.PortfolioAnalyzedEvent;
import grit.stockIt.domain.mission.event.RankerAchievedEvent;
import grit.stockIt.domain.mission.event.StockAnalyzedEvent;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class MissionEventListener {

    private final MissionService missionService;
    private final MissionProgressService missionProgressService;

    /**
     * 주식 '체결 완료' 이벤트를 수신합니다.
     * 체결 트랜잭션 커밋 후(AFTER_COMMIT) 별도 스레드(@Async)에서 실행하여, 미션 로직의 실패가
     * 체결 트랜잭션을 롤백시키지 못하게 한다. 단, 커밋 이후 실행이라 실패 시 갱신은 유실될 수 있다.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTradeCompletionEvent(TradeCompletionEvent event) {
        try {
            missionProgressService.updateMissionProgress(event);
        } catch (Exception e) {
            log.error("거래 미션 진행도 갱신 실패: memberId={}", event.getMemberId(), e);
        }
    }

    /**
     * 회원 가입 완료 이벤트를 수신하여 신규 회원 미션을 초기화합니다.
     * 동기 리스너 — 가입 트랜잭션에 참여하며, 예외는 발행자(가입 서비스)에게 그대로 전파됩니다.
     */
    @EventListener
    public void handleMemberRegisteredEvent(MemberRegisteredEvent event) {
        log.info("Event Received: Member Registered for memberId={}", event.member().getMemberId());
        missionService.initializeMissionsForNewMember(event.member());
    }

    /**
     * 랭커 달성 이벤트를 수신하여 Top 10 명단의 랭커 미션 진행/완료를 처리합니다.
     * 동기 리스너 — 예외는 발행자(RankingService)에게 그대로 전파되어 기존 catch에 흡수됩니다.
     */
    @EventListener
    public void handleRankerAchievedEvent(RankerAchievedEvent event) {
        log.info("Event Received: Ranker Achieved for {} members", event.top10MemberIds().size());
        missionProgressService.processRankerAchievement(event.top10MemberIds());
    }
    /**
     * [신규] 종목 분석 완료 이벤트 수신
     * @Async를 붙여 WebFlux의 Non-blocking 스레드가 JPA(Blocking) 로직을 기다리지 않게 함
     */
    @Async
    @EventListener
    public void handleStockAnalyzedEvent(StockAnalyzedEvent event) {
        log.info("Event Received: Stock Analysis for {}", event.getEmail());
        try {
            missionService.handleReportView(event.getEmail());
        } catch (Exception e) {
            log.error("종목 분석 미션 처리 중 오류 발생", e);
        }
    }

    /**
     * [신규] 포트폴리오 분석 완료 이벤트 수신
     */
    @Async
    @EventListener
    public void handlePortfolioAnalyzedEvent(PortfolioAnalyzedEvent event) {
        log.info("Event Received: Portfolio Analysis for {}", event.getEmail());
        try {
            missionService.handlePortfolioAnalysis(event.getEmail());
        } catch (Exception e) {
            log.error("포트폴리오 분석 미션 처리 중 오류 발생", e);
        }
    }
}