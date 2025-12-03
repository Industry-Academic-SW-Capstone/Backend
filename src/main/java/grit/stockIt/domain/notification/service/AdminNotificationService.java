package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private final FcmService fcmService;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    // 모든 회원에게 알림을 전송
    @Async
    @Transactional
    public CompletableFuture<BroadcastResult> sendBroadcastNotification(
            List<Member> members, String title, String body) {
        
        int successCount = 0;
        int failCount = 0;
        int savedCount = 0;
        
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("type", "SYSTEM");
        data.put("timestamp", String.valueOf(System.currentTimeMillis()));
        
        String detailData = createDetailData(title, body);
        
        for (Member member : members) {
            try {
                // DB에 알림 저장 (모든 회원에게 저장)
                Notification notification = Notification.builder()
                        .member(member)
                        .type(NotificationType.SYSTEM)
                        .title(title)
                        .message(body)
                        .detailData(detailData)
                        .iconType("system")
                        .isRead(false)
                        .build();
                
                notificationRepository.save(notification);
                savedCount++;
                
                // FCM 푸시 알림 전송 (토큰이 있는 경우만)
                if (member.hasFcmToken()) {
                    // executionNotificationEnabled 체크 안 함
                    boolean success = fcmService.sendExecutionNotification(
                            member.getFcmToken(),
                            data
                    );
                    
                    if (success) {
                        successCount++;
                    } else {
                        failCount++;
                        log.warn("FCM 알림 전송 실패: memberId={}", member.getMemberId());
                    }
                }
                
            } catch (Exception e) {
                failCount++;
                log.error("알림 처리 중 오류 발생: memberId={}", member.getMemberId(), e);
            }
        }
        
        BroadcastResult result = new BroadcastResult(successCount, failCount, savedCount);
        log.info("=== 전체 알림 전송 완료: 성공={}, 실패={}, DB저장={} ===", 
                successCount, failCount, savedCount);
        
        return CompletableFuture.completedFuture(result);
    }
    
    // 알림 상세 데이터를 JSON으로 변환
    private String createDetailData(String title, String body) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("title", title);
        detailMap.put("body", body);
        detailMap.put("type", "SYSTEM");
        detailMap.put("sentAt", System.currentTimeMillis());
        
        try {
            return objectMapper.writeValueAsString(detailMap);
        } catch (JsonProcessingException e) {
            log.error("JSON 변환 실패", e);
            return "{}";
        }
    }
    
    // 알림 전송 결과를 담는 레코드
    public record BroadcastResult(
            int successCount,
            int failCount,
            int savedCount
    ) {}
}

