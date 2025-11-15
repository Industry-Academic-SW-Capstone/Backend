package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionNotificationService {

    private final FcmService fcmService;
    private final MemberRepository memberRepository;

    // 체결 완료 이벤트 수신
    @Async
    @EventListener
    @Transactional(readOnly = true)
    public void handleExecutionFilledEvent(ExecutionFilledEvent event) {
        log.debug("체결 완료 이벤트 수신: executionId={}, memberId={}", event.executionId(), event.memberId());

        try {
            // Member 조회
            memberRepository.findById(event.memberId())
                    .ifPresentOrElse(
                            member -> sendNotificationToMember(member, event), // 체결 알림 전송
                            () -> log.warn("Member를 찾을 수 없습니다: memberId={}", event.memberId())
                    );
        } catch (Exception e) {
            log.error("체결 알림 전송 실패: executionId={}, memberId={}", event.executionId(), event.memberId(), e);
        }
    }

    // 체결 알림 전송
    private void sendNotificationToMember(Member member, ExecutionFilledEvent event) {
        if (!member.hasFcmToken()) {
            log.debug("FCM 토큰이 등록되지 않은 사용자: memberId={}", member.getMemberId());
            return;
        }

        if (!member.isExecutionNotificationEnabled()) {
            log.debug("체결 알림이 비활성화된 사용자: memberId={}", member.getMemberId());
            return;
        }

        String orderMethod = event.orderMethod(); // BUY, SELL
        String orderMethodKorean = "BUY".equals(orderMethod) ? "매수" : "매도";
        
        Map<String, String> data = new HashMap<>();
        data.put("type", "EXECUTION");
        data.put("executionId", String.valueOf(event.executionId()));
        data.put("orderId", String.valueOf(event.orderId()));
        data.put("stockCode", event.stockCode());
        data.put("stockName", event.stockName());
        data.put("price", event.price().toString());
        data.put("quantity", String.valueOf(event.quantity()));
        data.put("orderMethod", orderMethod);
        data.put("executedAt", String.valueOf(System.currentTimeMillis()));

        boolean success = fcmService.sendExecutionNotification( // FCM 푸시 알림 전송
                member.getFcmToken(),
                event.stockName(),
                orderMethodKorean,
                event.quantity(),
                formatPrice(event.price()),
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

