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
            
            // 구독 메시지 전송 (중복 구독은 KIS API가 처리)
            // subscribedStocks 체크를 제거하여 항상 구독 메시지 전송
            // 이렇게 하면 연결이 끊어진 후 재연결 시에도 정상 작동
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
            
            // 파이프 구분 형식인지 확인 (실시간 데이터)
            if (payload.startsWith("0|") || payload.startsWith("1|")) {
                handleRealtimeData(payload);
                return;
            }
            
            // JSON 파싱 (초기 응답)
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
     * 실시간 데이터 처리 (파이프 구분 형식)
     * 형식: 0|H0STCNT0|001|005930^103659^103800^2^3300^3.28^...
     * 파이프(|)로 먼저 구분 후, 실제 데이터는 캐럿(^)으로 구분
     */
    private void handleRealtimeData(String payload) {
        try {
            // 파이프로 split (메타데이터 구분)
            String[] parts = payload.split("\\|");
            
            if (parts.length < 4) {
                log.warn("KIS 데이터 형식 오류: 파이프 구분 필드 부족 ({}개)", parts.length);
                return;
            }
            
            // 암호화 여부 (0: 미암호화, 1: 암호화)
            String encrypted = parts[0];
            if ("1".equals(encrypted)) {
                log.warn("암호화된 데이터 수신 (현재 미지원)");
                return;
            }
            
            // parts[1]: TR ID (H0STCNT0)
            // parts[2]: 데이터 건수 (001, 002 등)
            // parts[3]: 실제 데이터 (캐럿으로 구분)
            
            // 실제 데이터를 캐럿으로 split
            String[] dataFields = parts[3].split("\\^");
            
            if (dataFields.length < 15) {
                log.debug("KIS 데이터 필드 부족: {}개 (최소 15개 필요)", dataFields.length);
                return;
            }
            
            // KIS API Response Body 필드 순서 (이미지 문서 기준)
            String stockCode = dataFields[0];        // MKSC_SHRN_ISCD: 종목코드
            // dataFields[1]: STCK_CNTG_HOUR: 체결시간
            String currentPrice = dataFields[2];     // STCK_PRPR: 현재가
            String changeSign = dataFields[3];       // PRDY_VRSS_SIGN: 전일대비부호 (1:상한 2:상승 3:보합 4:하한 5:하락)
            String changeAmount = dataFields[4];     // PRDY_VRSS: 전일대비
            String changeRate = dataFields[5];       // PRDY_CTRT: 전일대비율
            // dataFields[6]: WGHN_AVRG_STCK_PRC: 가중평균주식가격
            // dataFields[7-11]: 시가, 고가, 저가, 매도호가, 매수호가 등
            // dataFields[12]: CNTG_VOL: 체결량
            String volume = dataFields.length > 13 ? dataFields[13] : "0";  // ACML_VOL: 누적거래량
            
            // DTO 변환
            StockPriceUpdateDto updateDto = StockPriceUpdateDto.from(
                    stockCode,
                    "", // 종목명은 클라이언트가 이미 알고 있음
                    parseIntValue(currentPrice),
                    parseIntValue(changeAmount),
                    changeRate,
                    changeSign,
                    parseLongValue(volume)
            );
            
            // 클라이언트에게 브로드캐스트
            messagingTemplate.convertAndSend(
                    "/topic/stock/" + updateDto.stockCode(),
                    updateDto
            );
            
            log.debug("시세 업데이트 전송: {} - {}원 ({})", 
                    updateDto.stockCode(), updateDto.currentPrice(), updateDto.changeSign());
            
        } catch (Exception e) {
            log.error("실시간 데이터 파싱 실패: {}", payload, e);
        }
    }
    
    /**
     * 연결 종료 처리
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("KIS 웹소켓 연결 종료: {}", status);
        kisSession = null;
        
        // 연결이 끊어지면 구독 상태도 초기화
        // 재연결 시 자동으로 재구독됨
        subscribedStocks.clear();
        log.info("KIS 구독 상태 초기화");
        
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

