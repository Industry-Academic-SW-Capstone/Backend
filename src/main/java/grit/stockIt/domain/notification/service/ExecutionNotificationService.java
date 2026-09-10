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
        // 알림 제목과 내용 (순수 계산은 팩토리가 담당)
        String title = ExecutionNotificationMessageFactory.title(event.stockName(), event.orderMethod());
        String message = ExecutionNotificationMessageFactory.body(
                event.orderMethod(), event.quantity(), event.price());

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
        Map<String, Object> detailMap = ExecutionNotificationMessageFactory.detailMap(event);

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
            log.debug("FcmService를 사용할 수 없습니다. 알림을 전송하지 않습니다.");
            return;
        }
        
        if (!member.hasFcmToken()) {
            log.debug("FCM 토큰이 등록되지 않은 사용자: memberId={}", member.getMemberId());
            return;
        }

        if (!member.isExecutionNotificationEnabled()) {
            log.debug("체결 알림이 비활성화된 사용자: memberId={}", member.getMemberId());
            return;
        }

        // 알림 제목과 내용 (DB 저장분과 동일한 팩토리 계산을 사용)
        String title = ExecutionNotificationMessageFactory.title(event.stockName(), event.orderMethod());
        String body = ExecutionNotificationMessageFactory.body(
                event.orderMethod(), event.quantity(), event.price());

        // FCM 페이로드 (title, body 는 PWA Service Worker 에서 사용)
        Map<String, String> data = ExecutionNotificationMessageFactory.fcmData(
                event, title, body, System.currentTimeMillis());

        boolean success = fcmService.sendExecutionNotification( // FCM 푸시 알림 전송 (Data-Only)
                member.getFcmToken(),
                data
        );

        if (!success) {
            log.warn("FCM 알림 전송 실패: memberId={}, executionId={}", member.getMemberId(), event.executionId());
        }
    }
}

