package grit.stockIt.global.websocket.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.stock.dto.StockOrderBookDto;
import grit.stockIt.domain.stock.dto.StockPriceUpdateDto;
import grit.stockIt.global.auth.KisTokenManager;
import grit.stockIt.global.websocket.dto.KisWebSocketRequest;
import grit.stockIt.global.websocket.dto.KisWebSocketResponse;
import grit.stockIt.global.websocket.manager.WebSocketSubscriptionManager;
import grit.stockIt.domain.matching.event.LimitOrderFillEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// KIS 웹소켓 클라이언트
@Slf4j
@Component
@RequiredArgsConstructor
public class KisWebSocketClient extends TextWebSocketHandler {
    
    private final KisTokenManager kisTokenManager;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;
    private final WebSocketSubscriptionManager subscriptionManager;
    private final ApplicationEventPublisher applicationEventPublisher;
    
    private WebSocketSession kisSession;
    private final Set<String> subscribedStocks = Collections.synchronizedSet(new HashSet<>()); // 체결가 구독 종목
    private final Set<String> subscribedOrderBooks = Collections.synchronizedSet(new HashSet<>()); // 호가 구독 종목
    
    // 재연결 관련 필드
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_RECONNECT_DELAY = 2000; // 2초
    
    // KIS API 실시간 데이터 필드 관련 상수
    private static final int MIN_REALTIME_DATA_FIELDS = 15; // KIS API 실시간 체결 데이터 최소 필드 개수
    private static final int FIELD_INDEX_STOCK_CODE = 0;    // 종목코드
    private static final int FIELD_INDEX_CURRENT_PRICE = 2; // 현재가
    private static final int FIELD_INDEX_CHANGE_SIGN = 3;   // 전일대비부호
    private static final int FIELD_INDEX_CHANGE_AMOUNT = 4; // 전일대비
    private static final int FIELD_INDEX_CHANGE_RATE = 5;   // 전일대비율
    private static final int FIELD_INDEX_CNTG_VOL = 12;     // 체결 거래량
    private static final int FIELD_INDEX_VOLUME = 13;       // 누적거래량
    private static final int FIELD_INDEX_TRADE_DIRECTION = 21; // 체결구분
    private static final int FIELD_INDEX_BIZ_DATE = 33;        // 영업일자
    
    private static final String KIS_WS_URL = "ws://ops.koreainvestment.com:21000";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
    
    // 종목 구독
    public synchronized void subscribe(String stockCode) {
        try {
            log.info("종목 구독 요청: {}", stockCode);
            
            // 연결 확인 및 연결
            if (!isConnected()) {
                log.info("KIS 연결 없음. 연결 시작...");
                connectToKis();
            }
            
            // 구독 메시지 전송
            // subscribedStocks는 KIS 서버로부터 "SUBSCRIBE SUCCESS" 응답을 받은 후에만 추가됨
            sendSubscribeMessage(stockCode);
            
            log.info("KIS 구독 요청 전송: {} (응답 대기 중)", stockCode);
            
        } catch (Exception e) {
            log.error("종목 구독 실패: {}", stockCode, e);
        }
    }

   // 종목 구독 해제
    public synchronized void unsubscribe(String stockCode) {
        try {
            if (!subscribedStocks.contains(stockCode)) {
                return;
            }
            
            if (isConnected()) {
                sendUnsubscribeMessage(stockCode);
            }
            
            subscribedStocks.remove(stockCode);
            log.info("KIS 구독 해제: {} (남은 체결가 {}개, 호가 {}개)", 
                    stockCode, subscribedStocks.size(), subscribedOrderBooks.size());
            
            // 체결가와 호가 모두 구독 종목이 없으면 연결 해제
            if (subscribedStocks.isEmpty() && subscribedOrderBooks.isEmpty()) {
                log.info("구독 종목 없음. KIS 연결 해제");
                disconnectFromKis();
            }
            
        } catch (Exception e) {
            log.error("종목 구독 해제 실패: {}", stockCode, e);
        }
    }
    
    // 호가 구독
    public synchronized void subscribeOrderBook(String stockCode) {
        try {
            log.info("호가 구독 요청: {}", stockCode);
            
            if (!isConnected()) {
                log.info("KIS 연결 없음. 연결 시작...");
                connectToKis();
            }
            
            // 구독 메시지 전송
            // subscribedOrderBooks는 KIS 서버로부터 "SUBSCRIBE SUCCESS" 응답을 받은 후에만 추가됨
            sendOrderBookSubscribeMessage(stockCode);
            
            log.info("KIS 호가 구독 요청 전송: {} (응답 대기 중)", stockCode);
            
        } catch (Exception e) {
            log.error("호가 구독 실패: {}", stockCode, e);
        }
    }
    
    // 호가 구독 해제
    public synchronized void unsubscribeOrderBook(String stockCode) {
        try {
            if (!subscribedOrderBooks.contains(stockCode)) {
                return;
            }
            
            if (isConnected()) {
                sendOrderBookUnsubscribeMessage(stockCode);
            }
            
            subscribedOrderBooks.remove(stockCode);
            log.info("KIS 호가 구독 해제: {} (남은 {}개)", stockCode, subscribedOrderBooks.size());
            
            // 체결가와 호가 모두 구독 종목이 없으면 연결 해제
            if (subscribedStocks.isEmpty() && subscribedOrderBooks.isEmpty()) {
                log.info("구독 종목 없음. KIS 연결 해제");
                disconnectFromKis();
            }
            
        } catch (Exception e) {
            log.error("호가 구독 해제 실패: {}", stockCode, e);
        }
    }
    
    // KIS 웹소켓 연결
    private void connectToKis() {
        try {
            log.info("KIS 웹소켓 연결 시작");
            
            // 새로운 웹소켓 세션이 생성되므로 Approval Key를 새로 발급
            // 기존 키는 이전 연결에서 사용되었으므로 무효화됨
            kisTokenManager.refreshApprovalKey();
            
            StandardWebSocketClient client = new StandardWebSocketClient();
            kisSession = client.execute(this, KIS_WS_URL).get();
            
            log.info("KIS 웹소켓 연결 완료");
            
        } catch (Exception e) {
            log.error("KIS 웹소켓 연결 실패", e);
            kisSession = null;
        }
    }
   
    // 구독 메시지 전송
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
    
    // 구독 해제 메시지 전송
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
    
    // 호가 구독 메시지 전송
    private void sendOrderBookSubscribeMessage(String stockCode) {
        try {
            String approvalKey = kisTokenManager.getApprovalKey();
            KisWebSocketRequest request = KisWebSocketRequest.subscribeOrderBook(approvalKey, stockCode);
            String json = objectMapper.writeValueAsString(request);
            
            kisSession.sendMessage(new TextMessage(json));
            log.debug("KIS 호가 구독 메시지 전송: {}", stockCode);
            
        } catch (Exception e) {
            log.error("호가 구독 메시지 전송 실패: {}", stockCode, e);
        }
    }
    
    // 호가 구독 해제 메시지 전송
    private void sendOrderBookUnsubscribeMessage(String stockCode) {
        try {
            String approvalKey = kisTokenManager.getApprovalKey();
            KisWebSocketRequest request = KisWebSocketRequest.unsubscribeOrderBook(approvalKey, stockCode);
            String json = objectMapper.writeValueAsString(request);
            
            kisSession.sendMessage(new TextMessage(json));
            log.debug("KIS 호가 구독 해제 메시지 전송: {}", stockCode);
            
        } catch (Exception e) {
            log.error("호가 구독 해제 메시지 전송 실패: {}", stockCode, e);
        }
    }
    
    // KIS로부터 메시지 수신
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
            
            // 에러 응답 처리 (rtCd=1은 에러를 의미)
            if (response != null && response.body() != null && "1".equals(response.body().rtCd())) {
                String msg1 = response.body().msg1();
                String trKey = response.header() != null ? response.header().trKey() : "unknown";
                
                if (msg1 != null) {
                    if (msg1.contains("SUBSCRIBE ERROR")) {
                        log.warn("KIS 구독 실패: {} - {}", trKey, msg1);
                        // 구독 실패 시 상태에서 제거 (혹시 추가되어 있다면)
                        subscribedStocks.remove(trKey);
                        subscribedOrderBooks.remove(trKey);
                        return;
                    } else if (msg1.contains("UNSUBSCRIBE ERROR")) {
                        log.warn("KIS 구독 해제 실패: {} - {} (이미 해제되었거나 구독되지 않은 종목일 수 있음)", trKey, msg1);
                        // 구독 해제 실패는 상태 불일치를 의미하므로, 로컬 상태에서 제거
                        subscribedStocks.remove(trKey);
                        subscribedOrderBooks.remove(trKey);
                        return;
                    } else {
                        log.warn("KIS 에러 응답: {} - {} ({})", trKey, msg1, response.body().msgCd());
                        return;
                    }
                }
            }
            
            // 구독 성공 응답 처리
            if (response != null && response.body() != null && "SUBSCRIBE SUCCESS".equals(response.body().msg1())) {
                String trKey = response.header() != null ? response.header().trKey() : null;
                if (trKey != null) {
                    // 구독 성공 확인 후 상태 업데이트
                    String trId = response.header().trId();
                    if ("H0STCNT0".equals(trId)) {
                        subscribedStocks.add(trKey);
                        log.info("KIS 구독 성공 확인: {} (체결가, 총 {}개)", trKey, subscribedStocks.size());
                    } else if ("H0STASP0".equals(trId)) {
                        subscribedOrderBooks.add(trKey);
                        log.info("KIS 구독 성공 확인: {} (호가, 총 {}개)", trKey, subscribedOrderBooks.size());
                    }
                }
                return;
            }
            
            // Null 체크 (output이 null인 경우는 에러 응답이거나 초기 응답이 아닌 경우)
            if (response == null || response.body() == null || response.body().output() == null) {
                // 에러 응답은 이미 위에서 처리했으므로, 여기서는 알 수 없는 형식만 로깅
                log.debug("KIS 응답에 output이 없습니다 (에러 응답이거나 PINGPONG일 수 있음). response: {}", response);
                return;
            }
            
            // TR ID로 초기 응답 분기 처리
            String trId = response.header().trId();
            if ("H0STCNT0".equals(trId)) {
                // 체결가 초기 응답 처리
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
                
                messagingTemplate.convertAndSend(
                        "/topic/stock/" + updateDto.stockCode(),
                        updateDto
                );
                
                log.debug("체결가 초기 응답 전송: {} - {}원", updateDto.stockCode(), updateDto.currentPrice());
            } else if ("H0STASP0".equals(trId)) {
                // 호가 초기 응답은 JSON 형식이 아닐 수 있음 (파이프 형식으로만 올 수도 있음)
                // 호가 초기 응답이 JSON으로 오는 경우를 처리하려면 KisWebSocketResponse에 호가 필드 추가 필요
                log.debug("호가 초기 응답 수신 (현재 JSON 형식 호가 초기 응답 미지원): {}", response.header().trKey());
            } else {
                log.warn("알 수 없는 TR ID의 초기 응답: {}", trId);
            }
            
        } catch (Exception e) {
            log.error("KIS 메시지 처리 실패", e);
        }
    }
    
    // 실시간 데이터 처리
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
            
            // parts[1]: TR ID (H0STCNT0 또는 H0STASP0)
            // parts[2]: 데이터 건수 (001, 002 등)
            // parts[3]: 실제 데이터 (캐럿으로 구분)
            
            String trId = parts[1];
            String[] dataFields = parts[3].split("\\^");
            
            // TR ID별 분기 처리
            if ("H0STCNT0".equals(trId)) {
                handlePriceData(dataFields);
            } else if ("H0STASP0".equals(trId)) {
                handleOrderBookData(dataFields);
            } else {
                log.warn("알 수 없는 TR ID: {}", trId);
            }
            
        } catch (Exception e) {
            log.error("실시간 데이터 파싱 실패: {}", payload, e);
        }
    }
   
    // 체결가 데이터 처리
    private void handlePriceData(String[] dataFields) {
        try {
            if (dataFields.length < MIN_REALTIME_DATA_FIELDS) {
                log.debug("KIS 체결가 데이터 필드 부족: {}개 (최소 {}개 필요)", 
                        dataFields.length, MIN_REALTIME_DATA_FIELDS);
                return;
            }
            
            // KIS API Response Body 필드 순서 (이미지 문서 기준)
            String stockCode = dataFields[FIELD_INDEX_STOCK_CODE];        // MKSC_SHRN_ISCD: 종목코드
            // dataFields[1]: STCK_CNTG_HOUR: 체결시간
            String currentPrice = dataFields[FIELD_INDEX_CURRENT_PRICE];  // STCK_PRPR: 현재가
            String changeSign = dataFields[FIELD_INDEX_CHANGE_SIGN];      // PRDY_VRSS_SIGN: 전일대비부호 (1:상한 2:상승 3:보합 4:하한 5:하락)
            String changeAmount = dataFields[FIELD_INDEX_CHANGE_AMOUNT];  // PRDY_VRSS: 전일대비
            String changeRate = dataFields[FIELD_INDEX_CHANGE_RATE];      // PRDY_CTRT: 전일대비율
            // dataFields[6]: WGHN_AVRG_STCK_PRC: 가중평균주식가격
            // dataFields[7-11]: 시가, 고가, 저가, 매도호가, 매수호가 등
            // dataFields[12]: CNTG_VOL: 체결량
            String volume = dataFields.length > FIELD_INDEX_VOLUME ? 
                    dataFields[FIELD_INDEX_VOLUME] : "0";  // ACML_VOL: 누적거래량
            String tradeVolume = dataFields.length > FIELD_INDEX_CNTG_VOL ?
                    dataFields[FIELD_INDEX_CNTG_VOL] : "0"; // CNTG_VOL
            String tradeDirection = dataFields.length > FIELD_INDEX_TRADE_DIRECTION ?
                    dataFields[FIELD_INDEX_TRADE_DIRECTION] : null;
            String tradeTime = dataFields.length > 1 ? dataFields[1] : null;
            String businessDate = dataFields.length > FIELD_INDEX_BIZ_DATE ?
                    dataFields[FIELD_INDEX_BIZ_DATE] : null;
            
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

            publishLimitOrderEvent(
                    stockCode,
                    currentPrice,
                    tradeVolume,
                    tradeDirection,
                    tradeTime,
                    businessDate
            );
            
        } catch (Exception e) {
            log.error("체결가 데이터 파싱 실패", e);
        }
    }
    
    // 호가 데이터 처리
    private void handleOrderBookData(String[] dataFields) {
        try {
            if (dataFields.length < 3) {
                log.debug("KIS 호가 데이터 필드 부족: {}개 (최소 3개 필요)", dataFields.length);
                return;
            }
            
            StockOrderBookDto orderBookDto = StockOrderBookDto.from(dataFields);
            
            // 클라이언트에게 브로드캐스트
            messagingTemplate.convertAndSend(
                    "/topic/stock/" + orderBookDto.stockCode() + "/orderbook",
                    orderBookDto
            );
            
            log.debug("호가 업데이트 전송: {} - 매도1: {}, 매수1: {}", 
                    orderBookDto.stockCode(),
                    orderBookDto.askLevels().isEmpty() ? null : orderBookDto.askLevels().get(0).price(),
                    orderBookDto.bidLevels().isEmpty() ? null : orderBookDto.bidLevels().get(0).price());
            
        } catch (Exception e) {
            log.error("호가 데이터 파싱 실패", e);
        }
    }
    
    // 연결 종료 처리
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.warn("KIS 웹소켓 연결 종료: {} (코드: {}, 이유: {})", 
                status, status.getCode(), status.getReason());
        kisSession = null;
        
        // 구독 목록은 유지 (재연결 시 재구독에 사용)
        log.info("KIS 연결 종료 - 구독 목록 유지: 체결가 {}개, 호가 {}개", 
                subscribedStocks.size(), subscribedOrderBooks.size());
        
        // 비정상 종료인 경우에만 자동 재연결 시도
        if (!status.equals(CloseStatus.NORMAL)) {
            log.info("비정상 종료 감지 - 재연결 시도 예약");
            scheduleReconnect();
        }
    }
    
    // 연결 상태 확인
    public boolean isConnected() {
        return kisSession != null && kisSession.isOpen();
    }
    
    // 구독 종목이 있는지 확인
    public boolean hasSubscriptions() {
        return !subscribedStocks.isEmpty() || !subscribedOrderBooks.isEmpty();
    }
    
    // KIS 웹소켓 연결 해제
    private void disconnectFromKis() {
        if (!isConnected()) {
            log.debug("이미 연결 해제됨");
            return;
        }
        
        try {
            log.info("KIS 웹소켓 연결 해제 시작");
            
            // 정상 종료 
            kisSession.close(CloseStatus.NORMAL);
            kisSession = null;
            
            log.info("KIS 웹소켓 연결 해제 완료");
            
        } catch (Exception e) {
            log.error("KIS 연결 해제 실패", e);
            kisSession = null;
        }
    }
    
    // 재연결 예약
    private void scheduleReconnect() {
        if (isReconnecting.get()) {
            log.debug("이미 재연결 시도 중");
            return;
        }
        
        int attempts = reconnectAttempts.get();
        if (attempts >= MAX_RECONNECT_ATTEMPTS) {
            log.error("최대 재연결 시도 횟수 초과 ({}회) - 재연결 중단", MAX_RECONNECT_ATTEMPTS);
            reconnectAttempts.set(0);
            return;
        }
        
        long delay = INITIAL_RECONNECT_DELAY * (long) Math.pow(2, attempts);
        log.info("{}초 후 재연결 시도 ({}/{}회)", delay / 1000, attempts + 1, MAX_RECONNECT_ATTEMPTS);
        
        new Thread(() -> {
            try {
                Thread.sleep(delay);
                reconnect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("재연결 대기 중 인터럽트 발생", e);
            }
        }, "KIS-Reconnect-Thread").start();
    }
    
    // KIS 서버 재연결
    public synchronized void reconnect() {
        if (isConnected()) {
            log.debug("이미 연결되어 있음 - 재연결 불필요");
            reconnectAttempts.set(0);
            return;
        }
        
        if (!isReconnecting.compareAndSet(false, true)) {
            log.debug("이미 재연결 시도 중");
            return;
        }
        
        try {
            log.info("KIS 서버 재연결 시도 중... ({}/{}회)", 
                    reconnectAttempts.get() + 1, MAX_RECONNECT_ATTEMPTS);
            
            // 재연결 시 새로운 웹소켓 세션이 생성되므로 approval key를 새로 발급
            kisTokenManager.refreshApprovalKey();
            
            connectToKis();
            
            if (isConnected()) {
                log.info("KIS 재연결 성공!");
                reconnectAttempts.set(0);
                resubscribeAll();
            } else {
                log.warn("KIS 재연결 실패");
                reconnectAttempts.incrementAndGet();
                scheduleReconnect();
            }
            
        } catch (Exception e) {
            log.error("재연결 중 오류 발생", e);
            reconnectAttempts.incrementAndGet();
            scheduleReconnect();
        } finally {
            isReconnecting.set(false);
        }
    }
    
    // 모든 구독 종목 재구독
    private void resubscribeAll() {
        try {
            // WebSocketSubscriptionManager의 구독 목록과 내부 구독 목록을 합침
            // subscriptionManager에는 연결 끊김 직전에 구독 요청이 있었으나 subscribedStocks에 반영되지 못한 종목도 포함됨
            Set<String> allStocks = new HashSet<>(subscriptionManager.getAllSubscribedStocks());
            allStocks.addAll(subscribedStocks);
            
            if (allStocks.isEmpty() && subscribedOrderBooks.isEmpty()) {
                log.info("재구독할 종목 없음");
                return;
            }
            
            log.info("종목 재구독 시작: 체결가 {}개, 호가 {}개", allStocks.size(), subscribedOrderBooks.size());
            
            // 기존 구독 목록 초기화 후 재구독
            // allStocks를 사용하여 subscriptionManager의 상태까지 포함한 모든 종목 재구독
            Set<String> stocksToResubscribe = new HashSet<>(allStocks);
            Set<String> orderBooksToResubscribe = new HashSet<>(subscribedOrderBooks);
            subscribedStocks.clear();
            subscribedOrderBooks.clear();
            
            // 체결가 재구독
            // 구독 성공은 KIS 서버 응답을 통해 확인되므로, 여기서는 요청만 전송
            for (String stockCode : stocksToResubscribe) {
                try {
                    sendSubscribeMessage(stockCode);
                    log.debug("체결가 재구독 요청 전송: {} (응답 대기 중)", stockCode);
                    Thread.sleep(100);
                } catch (Exception e) {
                    log.error("체결가 재구독 실패: {}", stockCode, e);
                }
            }
            
            // 호가 재구독
            // 구독 성공은 KIS 서버 응답을 통해 확인되므로, 여기서는 요청만 전송
            for (String stockCode : orderBooksToResubscribe) {
                try {
                    sendOrderBookSubscribeMessage(stockCode);
                    log.debug("호가 재구독 요청 전송: {} (응답 대기 중)", stockCode);
                    Thread.sleep(100);
                } catch (Exception e) {
                    log.error("호가 재구독 실패: {}", stockCode, e);
                }
            }
            
            log.info("전체 재구독 완료: 체결가 {}개, 호가 {}개", subscribedStocks.size(), subscribedOrderBooks.size());
            
        } catch (Exception e) {
            log.error("재구독 프로세스 실패", e);
        }
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

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.trim().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private void publishLimitOrderEvent(String stockCode,
                                        String priceStr,
                                        String quantityStr,
                                        String tradeDirection,
                                        String tradeTime,
                                        String businessDate) {
        OrderMethod takerMethod = resolveOrderMethod(tradeDirection);
        if (takerMethod == null) {
            return;
        }

        int quantity = parseIntValue(quantityStr);
        if (quantity <= 0) {
            return;
        }

        BigDecimal price = parseBigDecimal(priceStr);
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        long eventTimestamp = resolveEventTimestamp(businessDate, tradeTime);
        LimitOrderFillEventMessage eventMessage = new LimitOrderFillEventMessage(
                stockCode,
                UUID.randomUUID().toString(),
                takerMethod,
                price,
                quantity,
                eventTimestamp
        );
        applicationEventPublisher.publishEvent(eventMessage);
    }

    private OrderMethod resolveOrderMethod(String tradeDirection) {
        if (tradeDirection == null) {
            return null;
        }
        return switch (tradeDirection.trim()) {
            case "1" -> OrderMethod.BUY;
            case "5" -> OrderMethod.SELL;
            default -> null;
        };
    }

    private long resolveEventTimestamp(String businessDate, String tradeTime) {
        try {
            LocalDate date = businessDate != null && !businessDate.isBlank()
                    ? LocalDate.parse(businessDate.trim(), DATE_FORMAT)
                    : LocalDate.now();
            LocalTime time = tradeTime != null && !tradeTime.isBlank()
                    ? LocalTime.parse(tradeTime.trim(), TIME_FORMAT)
                    : LocalTime.now();
            LocalDateTime dateTime = LocalDateTime.of(date, time);
            return dateTime.atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}

