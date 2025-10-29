package grit.stockIt.global.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.stock.dto.StockPriceUpdateDto;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.websocket.dto.KisWebSocketRequest;
import grit.stockIt.global.websocket.dto.KisWebSocketResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * KIS 웹소켓 클라이언트
 * 한국투자증권 웹소켓 서버와 연결 및 통신 관리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KisWebSocketClient extends TextWebSocketHandler {
    
    private final KisTokenManager kisTokenManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    
    private WebSocketSession kisSession;
    private final Set<String> subscribedStocks = Collections.synchronizedSet(new HashSet<>());
    
    private static final String KIS_WS_URL = "ws://ops.koreainvestment.com:21000";
    
    /**
     * 종목 구독 (연결 없으면 자동 연결)
     */
    public synchronized void subscribe(String stockCode) {
        try {
            log.info("종목 구독 요청: {}", stockCode);
            
            // 연결 확인 및 연결
            if (!isConnected()) {
                log.info("KIS 연결 없음. 연결 시작...");
                connectToKis();
            }
            
            // 이미 구독 중이면 스킵
            if (subscribedStocks.contains(stockCode)) {
                log.debug("이미 구독 중: {}", stockCode);
                return;
            }
            
            // 구독 메시지 전송
            sendSubscribeMessage(stockCode);
            subscribedStocks.add(stockCode);
            
            log.info("KIS 구독 완료: {} (총 {}개)", stockCode, subscribedStocks.size());
            
        } catch (Exception e) {
            log.error("종목 구독 실패: {}", stockCode, e);
        }
    }
    
    /**
     * 종목 구독 해제
     */
    public synchronized void unsubscribe(String stockCode) {
        try {
            if (!subscribedStocks.contains(stockCode)) {
                return;
            }
            
            if (isConnected()) {
                sendUnsubscribeMessage(stockCode);
            }
            
            subscribedStocks.remove(stockCode);
            log.info("KIS 구독 해제: {} (남은 {}개)", stockCode, subscribedStocks.size());
            
            // 구독 종목이 없으면 5분 후 연결 해제 예약
            if (subscribedStocks.isEmpty()) {
                log.info("구독 종목 없음. 연결 유지 중...");
            }
            
        } catch (Exception e) {
            log.error("종목 구독 해제 실패: {}", stockCode, e);
        }
    }
    
    /**
     * KIS 웹소켓 연결
     */
    private void connectToKis() {
        try {
            log.info("KIS 웹소켓 연결 시작");
            
            StandardWebSocketClient client = new StandardWebSocketClient();
            kisSession = client.execute(this, KIS_WS_URL).get();
            
            log.info("KIS 웹소켓 연결 완료");
            
        } catch (Exception e) {
            log.error("KIS 웹소켓 연결 실패", e);
            kisSession = null;
        }
    }
    
    /**
     * 구독 메시지 전송
     */
    private void sendSubscribeMessage(String stockCode) {
        try {
            String approvalKey = kisTokenManager.getApprovalKey();
            KisWebSocketRequest request = KisWebSocketRequest.subscribe(approvalKey, stockCode);
            String json = objectMapper.writeValueAsString(request);
            
            kisSession.sendMessage(new TextMessage(json));
            log.debug("KIS 구독 메시지 전송: {}", stockCode);
            
        } catch (Exception e) {
            log.error("구독 메시지 전송 실패: {}", stockCode, e);
        }
    }
    
    /**
     * 구독 해제 메시지 전송
     */
    private void sendUnsubscribeMessage(String stockCode) {
        try {
            String approvalKey = kisTokenManager.getApprovalKey();
            KisWebSocketRequest request = KisWebSocketRequest.unsubscribe(approvalKey, stockCode);
            String json = objectMapper.writeValueAsString(request);
            
            kisSession.sendMessage(new TextMessage(json));
            log.debug("KIS 구독 해제 메시지 전송: {}", stockCode);
            
        } catch (Exception e) {
            log.error("구독 해제 메시지 전송 실패: {}", stockCode, e);
        }
    }
    
    /**
     * KIS로부터 메시지 수신
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            String payload = message.getPayload();
            
            // JSON 파싱
            KisWebSocketResponse response = objectMapper.readValue(payload, KisWebSocketResponse.class);
            
            // PINGPONG 메시지 처리 (Heartbeat)
            if (response != null && "PINGPONG".equals(response.header().trId())) {
                log.debug("KIS Heartbeat 수신: {}", response.header().datetime());
                return;
            }
            
            // 구독 성공 응답 처리
            if (response != null && response.body() != null && "SUBSCRIBE SUCCESS".equals(response.body().msg1())) {
                log.info("KIS 구독 성공 확인: {} ({})", response.header().trKey(), response.body().msgCd());
                return;
            }
            
            // Null 체크
            if (response == null || response.body() == null || response.body().output() == null) {
                log.warn("KIS 응답 포맷이 예상과 다릅니다. response: {}", response);
                return;
            }
            
            // DTO 변환
            var output = response.body().output();
            StockPriceUpdateDto updateDto = StockPriceUpdateDto.from(
                    output.stockCode(),
                    "", // 종목명은 클라이언트가 이미 알고 있음
                    parseIntValue(output.currentPrice()),
                    parseIntValue(output.changeAmount()),
                    output.changeRate(),
                    output.changeSign(),
                    parseLongValue(output.volume())
            );
            
            // 클라이언트에게 브로드캐스트
            messagingTemplate.convertAndSend(
                    "/topic/stock/" + updateDto.stockCode(),
                    updateDto
            );
            
            log.debug("시세 업데이트 전송: {} - {}원", updateDto.stockCode(), updateDto.currentPrice());
            
        } catch (Exception e) {
            log.error("KIS 메시지 처리 실패", e);
        }
    }
    
    /**
     * 연결 종료 처리
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("KIS 웹소켓 연결 종료: {}", status);
        kisSession = null;
        
        // TODO: 재연결 로직
    }
    
    /**
     * 연결 상태 확인
     */
    private boolean isConnected() {
        return kisSession != null && kisSession.isOpen();
    }
    
    private Integer parseIntValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    private Long parseLongValue(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

