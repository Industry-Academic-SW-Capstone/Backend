package grit.stockIt.global.websocket.monitor;

import grit.stockIt.global.websocket.client.KisWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 웹소켓 연결 상태 모니터링
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketConnectionMonitor {
    
    private final KisWebSocketClient kisWebSocketClient;
    
    // 30초마다 연결 상태 확인
    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void checkConnection() {
        try {
            if (!kisWebSocketClient.isConnected()) {
                // 구독 종목이 있을 때만 재연결 시도
                if (kisWebSocketClient.hasSubscriptions()) {
                    log.warn("KIS 웹소켓 연결 끊김 감지 - 재연결 시도 (구독 종목 있음)");
                    kisWebSocketClient.reconnect();
                } else {
                    log.debug("KIS 웹소켓 연결 끊김 - 구독 종목 없음으로 재연결 생략");
                }
            } else {
                log.debug("KIS 웹소켓 연결 정상");
            }
        } catch (Exception e) {
            log.error("연결 상태 확인 중 오류 발생", e);
        }
    }
    
    // 5분마다 연결 상태 로깅 (헬스체크)
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void logConnectionStatus() {
        boolean isConnected = kisWebSocketClient.isConnected();
        log.info("KIS 웹소켓 연결 상태: {}", isConnected ? "연결됨" : "연결 안됨");
    }
}

