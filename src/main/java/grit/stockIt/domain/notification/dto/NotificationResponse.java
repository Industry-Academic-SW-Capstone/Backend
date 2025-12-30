package grit.stockIt.domain.notification.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record NotificationResponse(
        Long notificationId,
        NotificationType type,
        String title,
        String message,
        @JsonRawValue  // JSON 문자열을 객체로 직접 반환
        String detailData,
        String iconType,
        boolean isRead,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return NotificationResponse.builder()
                .notificationId(notification.getNotificationId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .detailData(notification.getDetailData())
                .iconType(notification.getIconType())
                .isRead(notification.isRead())
                .readAt(notification.getReadAt())
                .createdAt(notification.getCreatedAt())
                .build();
    }
}

