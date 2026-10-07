package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;

// 체결 이벤트의 입구. KIS 피드와 부하 테스트가 같은 입구를 지나야 측정이 실제 경로를 잰다.
public interface FillCommandPublisher {

    FillDispatchResult publish(String stockCode, LimitOrderFillEvent event);
}
