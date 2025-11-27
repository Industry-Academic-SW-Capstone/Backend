package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.event.PortfolioAnalyzedEvent;
import grit.stockIt.domain.mission.event.StockAnalyzedEvent;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Async;

@Slf4j
@Component
@RequiredArgsConstructor
public class MissionEventListener {

    private final MissionService missionService;

    /**
     * 주식 '체결 완료' 이벤트를 수신합니다.
     */
    @EventListener
    public void handleTradeCompletionEvent(TradeCompletionEvent event) { // 2. [수정] 메서드명과 파라미터 변경
        // 모든 로직을 MissionService에 위임합니다.
        missionService.updateMissionProgress(event);
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