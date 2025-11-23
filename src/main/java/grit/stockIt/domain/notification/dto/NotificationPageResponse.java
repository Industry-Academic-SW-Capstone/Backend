package grit.stockIt.domain.notification.dto;

import lombok.Builder;
import org.springframework.data.domain.Page;

import java.util.List;

@Builder
public record NotificationPageResponse(
        List<NotificationResponse> notifications,
        int currentPage,
        int totalPages,
        long totalElements,
        boolean hasNext
) {
    public static NotificationPageResponse from(Page<NotificationResponse> page) {
        return NotificationPageResponse.builder()
                .notifications(page.getContent())
                .currentPage(page.getNumber())
                .totalPages(page.getTotalPages())
                .totalElements(page.getTotalElements())
                .hasNext(page.hasNext())
                .build();
    }
}

