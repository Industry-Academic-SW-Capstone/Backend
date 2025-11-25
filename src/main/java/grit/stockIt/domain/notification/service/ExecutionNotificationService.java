package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ExecutionNotificationService {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public ExecutionNotificationService(
            @Autowired(required = false) FcmService fcmService,
            MemberRepository memberRepository,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.fcmService = fcmService;
        this.memberRepository = memberRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
        if (fcmService == null) {
            log.info("FcmService를 사용할 수 없습니다. 알림 기능이 비활성화됩니다.");
        }
    }

    // 체결 완료 이벤트 수신
    @Async
    @EventListener
    @Transactional
    public void handleExecutionFilledEvent(ExecutionFilledEvent event) {
        log.debug("체결 완료 이벤트 수신: executionId={}, memberId={}, contestId={}", 
                event.executionId(), event.memberId(), event.contestId());

        try {
            // Member 조회
            Member member = memberRepository.findById(event.memberId())
                    .orElseThrow(() -> new IllegalStateException("Member를 찾을 수 없습니다: memberId=" + event.memberId()));
            
            processExecutionNotification(member, event);
        } catch (Exception e) {
            log.error("체결 알림 처리 실패: executionId={}, memberId={}", event.executionId(), event.memberId(), e);
            throw e;  // 예외 재던지기: 트랜잭션 롤백 발동
        }
    }

    // 체결 알림 처리 (DB 저장 + FCM 푸시)
    private void processExecutionNotification(Member member, ExecutionFilledEvent event) {
        saveNotificationToDatabase(member, event);
        sendFcmPushNotification(member, event);
    }

    // Notification 엔티티를 DB에 저장
    private void saveNotificationToDatabase(Member member, ExecutionFilledEvent event) {
        String orderMethodKorean = "BUY".equals(event.orderMethod()) ? "매수" : "매도";
        
        // 알림 제목
        String title = String.format("%s %s 체결", event.stockName(), orderMethodKorean);
        
        // 알림 내용
        String message = String.format("%s %d주가 %s원에 체결되었습니다", 
                orderMethodKorean, 
                event.quantity(), 
                formatPrice(event.price()));

        // 상세 데이터 (JSON)
        String detailData = createDetailData(event);

        // Notification 엔티티 생성
        Notification notification = Notification.builder()
                .member(member)
                .type(NotificationType.EXECUTION)
                .title(title)
                .message(message)
                .detailData(detailData)
                .iconType("execution_success")
                .isRead(false)
                .build();

        // DB 저장
        notificationRepository.save(notification);
        
        log.info("체결 알림 저장 완료: notificationId={}, memberId={}, executionId={}", 
                notification.getNotificationId(), member.getMemberId(), event.executionId());
    }

    // 상세 데이터를 JSON으로 변환
    private String createDetailData(ExecutionFilledEvent event) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("executionId", event.executionId());
        detailMap.put("orderId", event.orderId());
        detailMap.put("accountId", event.accountId());
        detailMap.put("contestId", event.contestId());
        detailMap.put("contestName", event.contestName());
        detailMap.put("stockCode", event.stockCode());
        detailMap.put("stockName", event.stockName());
        detailMap.put("price", event.price());
        detailMap.put("quantity", event.quantity());
        detailMap.put("orderMethod", event.orderMethod());

        try {
            return objectMapper.writeValueAsString(detailMap);
        } catch (JsonProcessingException e) {
            log.error("JSON 변환 실패: executionId={}", event.executionId(), e);
            throw new IllegalStateException("JSON 변환 중 오류 발생", e);
        }
    }

    // FCM 푸시 알림 전송
    private void sendFcmPushNotification(Member member, ExecutionFilledEvent event) {
        if (fcmService == null) {
            log.info("FcmService를 사용할 수 없습니다. 알림을 전송하지 않습니다. (FirebaseMessaging Bean이 없을 수 있습니다)");
            return;
        }
        
        if (!member.hasFcmToken()) {
            log.info("FCM 토큰이 등록되지 않은 사용자: memberId={}", member.getMemberId());
            return;
        }

        if (!member.isExecutionNotificationEnabled()) {
            log.info("체결 알림이 비활성화된 사용자: memberId={}", member.getMemberId());
            return;
        }

        String orderMethod = event.orderMethod(); // BUY, SELL
        String orderMethodKorean = "BUY".equals(orderMethod) ? "매수" : "매도";
        
        // 알림 제목과 내용 생성
        String title = String.format("%s %s 체결", event.stockName(), orderMethodKorean);
        String body = String.format("%s %d주가 %s원에 체결되었습니다", 
                orderMethodKorean, 
                event.quantity(), 
                formatPrice(event.price()));
        
        Map<String, String> data = new HashMap<>();
        // title, body를 data에 포함 (PWA Service Worker에서 사용)
        data.put("title", title);
        data.put("body", body);
        data.put("type", "EXECUTION");
        data.put("executionId", String.valueOf(event.executionId()));
        data.put("orderId", String.valueOf(event.orderId()));
        data.put("accountId", String.valueOf(event.accountId()));
        data.put("contestId", String.valueOf(event.contestId()));
        data.put("contestName", event.contestName());
        data.put("stockCode", event.stockCode());
        data.put("stockName", event.stockName());
        data.put("price", event.price().toString());
        data.put("quantity", String.valueOf(event.quantity()));
        data.put("orderMethod", orderMethod);
        data.put("executedAt", String.valueOf(System.currentTimeMillis()));

        boolean success = fcmService.sendExecutionNotification( // FCM 푸시 알림 전송 (Data-Only)
                member.getFcmToken(),
                data
        );

        if (!success) {
            log.warn("FCM 알림 전송 실패: memberId={}, executionId={}", member.getMemberId(), event.executionId());
        }
    }

    private String formatPrice(BigDecimal price) {
        return String.format("%,d", price.intValue());
    }
}

