package grit.stockIt.domain.notification.dto;

import lombok.Builder;

// 안 읽은 알림 개수 응답
@Builder
public record UnreadCountResponse(long unreadCount) {
    public static UnreadCountResponse of(long count) {
        return UnreadCountResponse.builder()
                .unreadCount(count)
                .build();
    }
}

