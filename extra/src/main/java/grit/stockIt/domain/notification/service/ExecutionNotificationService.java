package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.entity.NotificationSetting;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Slf4j
@Service
public class ExecutionNotificationService {

    private final FcmService fcmService;
    private final NotificationSettingService notificationSettingService;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public ExecutionNotificationService(
            @Autowired(required = false) FcmService fcmService,
            NotificationSettingService notificationSettingService,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.fcmService = fcmService;
        this.notificationSettingService = notificationSettingService;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    // 체결 완료 이벤트 수신
    // 알림 워커(NotificationEventConsumer)가 member.events 의 체결 이벤트로 부른다. 실패는 던져 다시 받게 한다.
    @Transactional
    public void handleExecutionFilledEvent(ExecutionFilledEvent event) {
        log.debug("체결 완료 이벤트 수신: executionId={}, memberId={}, contestId={}", 
                event.executionId(), event.memberId(), event.contestId());

        try {
            // 푸시 수신 설정. 행이 없으면 토큰 없음·체결 알림 켬으로 본다.
            NotificationSetting setting = notificationSettingService.settingOf(event.memberId());
            
            processExecutionNotification(setting, event);
        } catch (Exception e) {
            log.error("체결 알림 처리 실패: executionId={}, memberId={}", event.executionId(), event.memberId(), e);
            throw e;  // 예외 재던지기: 트랜잭션 롤백 발동
        }
    }

    // 체결 알림 처리 (DB 저장 + FCM 푸시)
    // 문구는 여기서 한 번만 계산해 두 경로가 같은 값을 쓴다.
    // 각 경로가 따로 계산하면 한쪽만 수정됐을 때 DB 내역과 푸시 문구가 갈린다.
    private void processExecutionNotification(NotificationSetting setting, ExecutionFilledEvent event) {
        String title = ExecutionNotificationMessageFactory.title(event.stockName(), event.orderMethod());
        String body = ExecutionNotificationMessageFactory.body(
                event.orderMethod(), event.quantity(), event.price());

        saveNotificationToDatabase(setting, event, title, body);
        sendFcmPushNotification(setting, event, title, body);
    }

    // Notification 엔티티를 DB에 저장
    private void saveNotificationToDatabase(NotificationSetting setting, ExecutionFilledEvent event,
                                            String title, String message) {
        // 상세 데이터 (JSON)
        String detailData = createDetailData(event);

        // Notification 엔티티 생성
        Notification notification = Notification.builder()
                .memberId(setting.getMemberId())
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
                notification.getNotificationId(), setting.getMemberId(), event.executionId());
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
    private void sendFcmPushNotification(NotificationSetting setting, ExecutionFilledEvent event,
                                         String title, String body) {
        if (fcmService == null) {
            log.debug("FcmService를 사용할 수 없습니다. 알림을 전송하지 않습니다.");
            return;
        }
        
        if (!setting.hasFcmToken()) {
            log.debug("FCM 토큰이 등록되지 않은 사용자: memberId={}", setting.getMemberId());
            return;
        }

        if (!setting.isExecutionNotificationEnabled()) {
            log.debug("체결 알림이 비활성화된 사용자: memberId={}", setting.getMemberId());
            return;
        }

        // FCM 페이로드 (title, body 는 PWA Service Worker 에서 사용)
        Map<String, String> data = ExecutionNotificationMessageFactory.fcmData(
                event, title, body, System.currentTimeMillis());

        boolean success = fcmService.sendExecutionNotification( // FCM 푸시 알림 전송 (Data-Only)
                setting.getFcmToken(),
                data
        );

        if (!success) {
            log.warn("FCM 알림 전송 실패: memberId={}, executionId={}", setting.getMemberId(), event.executionId());
        }
    }
}

