package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;
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
public class MissionNotificationService {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public MissionNotificationService(
            @Autowired(required = false) FcmService fcmService,
            MemberRepository memberRepository,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.fcmService = fcmService;
        this.memberRepository = memberRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    // 미션 완료 이벤트 수신
    @Async
    @EventListener
    @Transactional
    public void handleMissionCompletedEvent(MissionCompletedEvent event) {
        log.debug("미션 완료 이벤트 수신: missionId={}, memberId={}, missionName={}", 
                event.missionId(), event.memberId(), event.missionName());

        try {
            // Member 조회
            Member member = memberRepository.findById(event.memberId())
                    .orElseThrow(() -> new IllegalStateException("Member를 찾을 수 없습니다: memberId=" + event.memberId()));
            
            processMissionNotification(member, event);
        } catch (Exception e) {
            log.error("미션 알림 처리 실패: missionId={}, memberId={}", event.missionId(), event.memberId(), e);
            throw e;  // 예외 재던지기: 트랜잭션 롤백 발동
        }
    }

    // 미션 알림 처리 (DB 저장 + FCM 푸시)
    private void processMissionNotification(Member member, MissionCompletedEvent event) {
        saveNotificationToDatabase(member, event);
        sendFcmPushNotification(member, event);
    }

    // Notification 엔티티를 DB에 저장
    private void saveNotificationToDatabase(Member member, MissionCompletedEvent event) {
        // 알림 제목과 내용 (순수 계산은 팩토리가 담당)
        String title = MissionNotificationMessageFactory.title(event.missionName());
        String message = MissionNotificationMessageFactory.body(event.moneyAmount(), event.titleName());

        // 상세 데이터 (JSON)
        String detailData = createDetailData(event);

        // Notification 엔티티 생성
        Notification notification = Notification.builder()
                .member(member)
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
                notification.getNotificationId(), member.getMemberId(), event.missionId());
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
    private void sendFcmPushNotification(Member member, MissionCompletedEvent event) {
        if (fcmService == null) {
            log.debug("FcmService를 사용할 수 없습니다. 알림을 전송하지 않습니다.");
            return;
        }
        
        if (!member.hasFcmToken()) {
            log.debug("FCM 토큰이 등록되지 않은 사용자: memberId={}", member.getMemberId());
            return;
        }

        // 알림 제목과 내용 (DB 저장분과 동일한 팩토리 계산을 사용)
        String title = MissionNotificationMessageFactory.title(event.missionName());
        String body = MissionNotificationMessageFactory.body(event.moneyAmount(), event.titleName());

        // FCM 페이로드 (title, body 는 PWA Service Worker 에서 사용)
        Map<String, String> data = MissionNotificationMessageFactory.fcmData(
                event, title, body, System.currentTimeMillis());

        boolean success = fcmService.sendExecutionNotification( // FCM 푸시 알림 전송 (Data-Only)
                member.getFcmToken(),
                data
        );

        if (!success) {
            log.warn("FCM 알림 전송 실패: memberId={}, missionId={}", member.getMemberId(), event.missionId());
        }
    }
}

