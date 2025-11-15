package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.order.event.TradeCompletionEvent;
import grit.stockIt.domain.stock.event.TradeEvent; // 1. TradeEvent 임포트
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    // (향후 출석(LoginEvent) 등 다른 이벤트가 생기면 여기에 리스너 추가)
    // @EventListener
    // public void handleLoginEvent(LoginEvent event) {
    //     missionService.updateLoginMission(event.getMemberId());
    // }
}