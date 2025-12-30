package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class MarketNotificationService {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public MarketNotificationService(
            @Autowired(required = false) FcmService fcmService,
            MemberRepository memberRepository,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.fcmService = fcmService;
        this.memberRepository = memberRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    // 장 시작 알림을 모든 사용자에게 전송
    @Async
    @Transactional
    public void sendMarketOpenNotification() {
        log.info("=== 장 시작 알림 전송 시작 ===");
        
        String title = "장 시작 알림";
        String message = "주식 시장이 시작되었습니다. 오늘도 좋은 하루 되세요!";
        String iconType = "market_open";
        NotificationType type = NotificationType.MARKET_OPEN;
        
        sendNotificationToAllMembers(title, message, iconType, type);
        
        log.info("=== 장 시작 알림 전송 완료 ===");
    }

    // 장 마감 30분 전 알림을 모든 사용자에게 전송
    @Async
    @Transactional
    public void sendMarketCloseReminderNotification() {
        log.info("=== 장 마감 30분 전 알림 전송 시작 ===");
        
        String title = "장 마감 30분 전";
        String message = "장이 30분 후에 마감됩니다.";
        String iconType = "market_close";
        NotificationType type = NotificationType.MARKET_CLOSE_REMINDER;
        
        sendNotificationToAllMembers(title, message, iconType, type);
        
        log.info("=== 장 마감 30분 전 알림 전송 완료 ===");
    }

    // 모든 사용자에게 알림 전송 (DB 저장 + FCM 푸시)
    private void sendNotificationToAllMembers(String title, String message, String iconType, NotificationType notificationType) {
        try {
            // 모든 사용자 조회
            List<Member> allMembers = memberRepository.findAll();
            log.info("전체 사용자 수: {}", allMembers.size());

            int successCount = 0;
            int failCount = 0;
            int skippedCount = 0;

            for (Member member : allMembers) {
                try {
                    // FCM 토큰이 있는 사용자만 처리
                    if (!member.hasFcmToken()) {
                        skippedCount++;
                        continue;
                    }

                    // DB에 알림 저장
                    saveNotificationToDatabase(member, title, message, iconType, notificationType);

                    // FCM 푸시 알림 전송
                    boolean fcmSuccess = sendFcmPushNotification(member, title, message, notificationType);

                    if (fcmSuccess) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    log.error("사용자 알림 전송 실패: memberId={}", member.getMemberId(), e);
                    failCount++;
                }
            }

            log.info("알림 전송 결과 - 성공: {}, 실패: {}, 건너뜀(FCM 토큰 없음): {}", 
                    successCount, failCount, skippedCount);
        } catch (Exception e) {
            log.error("알림 전송 중 오류 발생", e);
            throw e;
        }
    }

    // Notification 엔티티를 DB에 저장
    private void saveNotificationToDatabase(Member member, String title, String message, 
                                           String iconType, NotificationType notificationType) {
        // 상세 데이터 (JSON)
        String detailData = createDetailData(notificationType);

        // Notification 엔티티 생성
        Notification notification = Notification.builder()
                .member(member)
                .type(notificationType)
                .title(title)
                .message(message)
                .detailData(detailData)
                .iconType(iconType)
                .isRead(false)
                .build();

        // DB 저장
        notificationRepository.save(notification);
        
        log.debug("알림 저장 완료: notificationId={}, memberId={}, type={}", 
                notification.getNotificationId(), member.getMemberId(), notificationType);
    }

    // 상세 데이터를 JSON으로 변환
    private String createDetailData(NotificationType notificationType) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("type", notificationType.name());
        detailMap.put("sentAt", System.currentTimeMillis());

        try {
            return objectMapper.writeValueAsString(detailMap);
        } catch (JsonProcessingException e) {
            log.error("JSON 변환 실패: type={}", notificationType, e);
            throw new IllegalStateException("JSON 변환 중 오류 발생", e);
        }
    }

    // FCM 푸시 알림 전송
    private boolean sendFcmPushNotification(Member member, String title, String message, 
                                          NotificationType notificationType) {
        if (fcmService == null) {
            log.debug("FcmService를 사용할 수 없습니다. 알림을 전송하지 않습니다.");
            return false;
        }
        
        if (!member.hasFcmToken()) {
            log.debug("FCM 토큰이 등록되지 않은 사용자: memberId={}", member.getMemberId());
            return false;
        }

        Map<String, String> data = new HashMap<>();
        // title, body를 data에 포함 (PWA Service Worker에서 사용)
        data.put("title", title);
        data.put("body", message);
        data.put("type", notificationType.name());
        data.put("sentAt", String.valueOf(System.currentTimeMillis()));

        boolean success = fcmService.sendExecutionNotification(
                member.getFcmToken(),
                data
        );

        if (!success) {
            log.warn("FCM 알림 전송 실패: memberId={}, type={}", member.getMemberId(), notificationType);
        }

        return success;
    }
}

