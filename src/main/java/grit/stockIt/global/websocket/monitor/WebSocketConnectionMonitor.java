package grit.stockIt.global.websocket.monitor;

import grit.stockIt.global.websocket.client.KisWebSocketClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 웹소켓 연결 상태 모니터링
 * 주기적으로 KIS 웹소켓 연결 상태를 확인하고 필요시 재연결
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketConnectionMonitor {
    
    private final KisWebSocketClient kisWebSocketClient;
    
    /**
     * 30초마다 연결 상태 확인
     * 연결이 끊어져 있고 재연결이 필요한 경우 재연결 시도
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void checkConnection() {
        try {
            if (!kisWebSocketClient.isConnected()) {
                log.warn("KIS 웹소켓 연결 끊김 감지 - 재연결 시도");
                kisWebSocketClient.reconnect();
            } else {
                log.debug("KIS 웹소켓 연결 정상");
            }
        } catch (Exception e) {
            log.error("연결 상태 확인 중 오류 발생", e);
        }
    }
    
    /**
     * 5분마다 연결 상태 로깅 (헬스체크)
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void logConnectionStatus() {
        boolean isConnected = kisWebSocketClient.isConnected();
        log.info("KIS 웹소켓 연결 상태: {}", isConnected ? "연결됨" : "연결 안됨");
    }
}

