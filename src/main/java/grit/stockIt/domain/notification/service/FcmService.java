package grit.stockIt.domain.notification.service;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

// Firebase Cloud Messaging을 사용하여 모바일 클라이언트에게 푸시 알림을 전송
@Slf4j
@Service
@RequiredArgsConstructor
public class FcmService {

    private final FirebaseMessaging firebaseMessaging;
    private final MemberRepository memberRepository;

    public boolean sendExecutionNotification(String fcmToken, String stockName, String orderMethod, Integer quantity, String price, Map<String, String> data) {
        String action = "매수".equals(orderMethod) ? "매수" : "매도";
        String title = stockName + " " + action + " 체결";
        String body = String.format("%s %d주가 %s원에 체결되었습니다", action, quantity, price);

        Notification notification = Notification.builder()
                .setTitle(title)
                .setBody(body)
                .build();

        Message message = Message.builder()
                .setToken(fcmToken)
                .setNotification(notification)
                .putAllData(data)
                .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder() // 안드로이드 알림 설정
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                        .setNotification(com.google.firebase.messaging.AndroidNotification.builder()
                                .setChannelId("execution_channel") // 안드로이드 알림 채널 설정
                                .setSound("default") // 안드로이드 알림 사운드 설정
                                .build())
                        .build())
                .setApnsConfig(com.google.firebase.messaging.ApnsConfig.builder() // iOS 알림 설정
                        .setAps(com.google.firebase.messaging.Aps.builder()
                                .setSound("default") // iOS 알림 사운드 설정
                                .setBadge(1) // iOS 알림 배지 설정
                                .build())
                        .build())
                .build();

        try {
            String response = firebaseMessaging.send(message);
            log.info("FCM 알림 전송 성공: token={}, response={}", fcmToken, response);
            return true;
        } catch (FirebaseMessagingException e) {
            log.error("FCM 알림 전송 실패: token={}, error={}", fcmToken, e.getMessage(), e);
            
            // 토큰이 무효한 경우 (만료, 삭제 등)
            MessagingErrorCode errorCode = e.getMessagingErrorCode();
            if (errorCode != null) {
                String errorCodeName = errorCode.name();
                if ("INVALID_REGISTRATION_TOKEN".equals(errorCodeName) ||
                    "REGISTRATION_TOKEN_NOT_REGISTERED".equals(errorCodeName) ||
                    "UNREGISTERED".equals(errorCodeName)) {
                    log.warn("FCM 토큰이 무효합니다. 토큰 삭제 처리: token={}, errorCode={}", fcmToken, errorCode);
                    try {
                        handleInvalidToken(fcmToken);
                    } catch (Exception ex) {
                        log.error("FCM 토큰 삭제 처리 중 오류 발생: token={}", fcmToken, ex);
                        // 토큰 삭제 실패가 알림 전송 실패에 영향을 주지 않도록 예외는 무시
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            log.error("FCM 알림 전송 중 예상치 못한 오류 발생: token={}", fcmToken, e);
            return false;
        }
    }

    // 데이터만 포함한 메시지 전송 (앱이 열려있을 때 사용), 알림 푸시 대신 데이터 푸시 사용
    public boolean sendDataMessage(String fcmToken, Map<String, String> data) {
        Message message = Message.builder()
                .setToken(fcmToken)
                .putAllData(data)
                .setAndroidConfig(com.google.firebase.messaging.AndroidConfig.builder()
                        .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
                        .build())
                .setApnsConfig(com.google.firebase.messaging.ApnsConfig.builder()
                        .setAps(com.google.firebase.messaging.Aps.builder()
                                .setContentAvailable(true)
                                .build())
                        .build())
                .build();

        try {
            String response = firebaseMessaging.send(message);
            log.info("FCM 데이터 메시지 전송 성공: token={}, response={}", fcmToken, response);
            return true;
        } catch (FirebaseMessagingException e) {
            log.error("FCM 데이터 메시지 전송 실패: token={}, error={}", fcmToken, e.getMessage(), e);
            
            MessagingErrorCode errorCode = e.getMessagingErrorCode();
            if (errorCode != null) {
                String errorCodeName = errorCode.name();
                if ("INVALID_REGISTRATION_TOKEN".equals(errorCodeName) ||
                    "REGISTRATION_TOKEN_NOT_REGISTERED".equals(errorCodeName) ||
                    "UNREGISTERED".equals(errorCodeName)) {
                    log.warn("FCM 토큰이 무효합니다. 토큰 삭제 처리: token={}, errorCode={}", fcmToken, errorCode);
                    try {
                        handleInvalidToken(fcmToken);
                    } catch (Exception ex) {
                        log.error("FCM 토큰 삭제 처리 중 오류 발생: token={}", fcmToken, ex);
                        // 토큰 삭제 실패가 알림 전송 실패에 영향을 주지 않도록 예외는 무시
                    }
                }
            }
            
            return false;
        } catch (Exception e) {
            log.error("FCM 데이터 메시지 전송 중 예상치 못한 오류 발생: token={}", fcmToken, e);
            return false;
        }
    }

    // 무효한 FCM 토큰 삭제 처리
    @Transactional
    public void handleInvalidToken(String fcmToken) {
        memberRepository.findByFcmToken(fcmToken)
                .ifPresent(member -> {
                    member.removeFcmToken();
                    log.info("무효한 FCM 토큰 삭제 완료: memberId={}, token={}", member.getMemberId(), fcmToken);
                });
    }
}

