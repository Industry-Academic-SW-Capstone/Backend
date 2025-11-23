package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.notification.dto.NotificationPageResponse;
import grit.stockIt.domain.notification.dto.NotificationResponse;
import grit.stockIt.domain.notification.dto.UnreadCountResponse;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import grit.stockIt.global.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final MemberRepository memberRepository;

    // 알림 목록 조회
    public NotificationPageResponse getNotifications(Long memberId, int page, int size) {
        Member member = findMemberById(memberId);

        Pageable pageable = PageRequest.of(page, size);
        Page<Notification> notificationPage = notificationRepository.findByMemberOrderByCreatedAtDesc(member, pageable);

        Page<NotificationResponse> responsePage = notificationPage.map(NotificationResponse::from);

        return NotificationPageResponse.from(responsePage);
    }

    // 읽지 않은 알림 개수 조회
    public UnreadCountResponse getUnreadCount(Long memberId) {
        Member member = findMemberById(memberId);
        long count = notificationRepository.countByMemberAndIsReadFalse(member);
        return UnreadCountResponse.of(count);
    }

    // 특정 알림 읽음 처리
    @Transactional
    public void markAsRead(Long notificationId, Long memberId) {
        Notification notification = findNotificationById(notificationId);

        // 본인의 알림만 읽음 처리 가능
        if (!notification.isOwnedBy(memberId)) {
            throw new BadRequestException("본인의 알림만 읽음 처리할 수 있습니다.");
        }

        // 이미 읽은 알림은 처리하지 않음
        if (notification.isRead()) {
            log.debug("이미 읽은 알림입니다: notificationId={}", notificationId);
            return;
        }

        notification.markAsRead();
        log.info("알림 읽음 처리 완료: notificationId={}, memberId={}", notificationId, memberId);
    }

    // 전체 알림 읽음 처리
    @Transactional
    public void markAllAsRead(Long memberId) {
        Member member = findMemberById(memberId);
        List<Notification> unreadNotifications = notificationRepository.findByMemberAndIsReadFalseOrderByCreatedAtDesc(member);

        unreadNotifications.forEach(Notification::markAsRead);

        log.info("전체 알림 읽음 처리 완료: memberId={}, count={}", memberId, unreadNotifications.size());
    }

    // 알림 삭제
    @Transactional
    public void deleteNotification(Long notificationId, Long memberId) {
        Notification notification = findNotificationById(notificationId);

        // 권한 체크: 본인의 알림만 삭제 가능
        if (!notification.isOwnedBy(memberId)) {
            throw new BadRequestException("본인의 알림만 삭제할 수 있습니다.");
        }

        notificationRepository.delete(notification);
        log.info("알림 삭제 완료: notificationId={}, memberId={}", notificationId, memberId);
    }

    // 타입별 알림 조회
    public List<NotificationResponse> getNotificationsByType(Long memberId, NotificationType type) {
        Member member = findMemberById(memberId);
        List<Notification> notifications = notificationRepository.findByMemberAndTypeOrderByCreatedAtDesc(member, type);

        return notifications.stream()
                .map(NotificationResponse::from)
                .toList();
    }

    // Member 조회 헬퍼 메서드
    private Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new BadRequestException("회원을 찾을 수 없습니다."));
    }

    // Notification 조회 헬퍼 메서드
    private Notification findNotificationById(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BadRequestException("알림을 찾을 수 없습니다."));
    }
}

