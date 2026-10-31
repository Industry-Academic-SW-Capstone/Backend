package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.entity.NotificationSetting;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Slf4j
@Service
public class MissionNotificationService {

    private final FcmService fcmService;
    private final NotificationSettingService notificationSettingService;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public MissionNotificationService(
            @Autowired(required = false) FcmService fcmService,
            NotificationSettingService notificationSettingService,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.fcmService = fcmService;
        this.notificationSettingService = notificationSettingService;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    // 미션 완료 이벤트 수신
    // 미션 완료는 같은 부가 서비스 안의 일이라 메모리 이벤트로 받는다. 미션 트랜잭션이 커밋된 뒤에만 보내고(롤백된 완료는 알리지 않는다),
    // 그 뒤의 저장은 새 트랜잭션이다. 미션 워커 스레드에서 이어서 돈다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleMissionCompletedEvent(MissionCompletedEvent event) {
        log.debug("미션 완료 이벤트 수신: missionId={}, memberId={}, missionName={}", 
                event.missionId(), event.memberId(), event.missionName());

        try {
            // 푸시 수신 설정. 행이 없으면 토큰 없음·체결 알림 켬으로 본다.
            NotificationSetting setting = notificationSettingService.settingOf(event.memberId());
            
            processMissionNotification(setting, event);
        } catch (Exception e) {
            log.error("미션 알림 처리 실패: missionId={}, memberId={}", event.missionId(), event.memberId(), e);
            throw e;  // 예외 재던지기: 트랜잭션 롤백 발동
        }
    }

    // 미션 알림 처리 (DB 저장 + FCM 푸시)
    // 문구는 여기서 한 번만 계산해 두 경로가 같은 값을 쓴다.
    // 각 경로가 따로 계산하면 한쪽만 수정됐을 때 DB 내역과 푸시 문구가 갈린다.
    private void processMissionNotification(NotificationSetting setting, MissionCompletedEvent event) {
        String title = MissionNotificationMessageFactory.title(event.missionName());
        String body = MissionNotificationMessageFactory.body(event.moneyAmount(), event.titleName());

        saveNotificationToDatabase(setting, event, title, body);
        sendFcmPushNotification(setting, event, title, body);
    }

    // Notification 엔티티를 DB에 저장
    private void saveNotificationToDatabase(NotificationSetting setting, MissionCompletedEvent event,
                                            String title, String message) {
        // 상세 데이터 (JSON)
        String detailData = createDetailData(event);

        // Notification 엔티티 생성
        Notification notification = Notification.builder()
                .memberId(setting.getMemberId())
                .type(NotificationType.MISSION_COMPLETED)
                .title(title)
                .message(message)
                .detailData(detailData)
                .iconType(MissionNotificationMessageFactory.iconType(event.track()))
                .isRead(false)
                .build();

        // DB 저장
        notificationRepository.save(notification);
        
        log.info("미션 알림 저장 완료: notificationId={}, memberId={}, missionId={}", 
                notification.getNotificationId(), setting.getMemberId(), event.missionId());
    }

    // 상세 데이터를 JSON으로 변환
    private String createDetailData(MissionCompletedEvent event) {
        Map<String, Object> detailMap = MissionNotificationMessageFactory.detailMap(event);

        try {
            return objectMapper.writeValueAsString(detailMap);
        } catch (JsonProcessingException e) {
            log.error("JSON 변환 실패: missionId={}", event.missionId(), e);
            throw new IllegalStateException("JSON 변환 중 오류 발생", e);
        }
    }

    // FCM 푸시 알림 전송
    private void sendFcmPushNotification(NotificationSetting setting, MissionCompletedEvent event,
                                         String title, String body) {
        if (fcmService == null) {
            log.debug("FcmService를 사용할 수 없습니다. 알림을 전송하지 않습니다.");
            return;
        }
        
        if (!setting.hasFcmToken()) {
            log.debug("FCM 토큰이 등록되지 않은 사용자: memberId={}", setting.getMemberId());
            return;
        }

        // FCM 페이로드 (title, body 는 PWA Service Worker 에서 사용)
        Map<String, String> data = MissionNotificationMessageFactory.fcmData(
                event, title, body, System.currentTimeMillis());

        boolean success = fcmService.sendExecutionNotification( // FCM 푸시 알림 전송 (Data-Only)
                setting.getFcmToken(),
                data
        );

        if (!success) {
            log.warn("FCM 알림 전송 실패: memberId={}, missionId={}", setting.getMemberId(), event.missionId());
        }
    }
}

