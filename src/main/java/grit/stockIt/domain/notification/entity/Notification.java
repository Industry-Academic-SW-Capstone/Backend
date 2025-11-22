package grit.stockIt.domain.notification.entity;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "notification",
    indexes = {
        @Index(name = "idx_member_created", columnList = "member_id, created_at DESC"),
        @Index(name = "idx_member_read", columnList = "member_id, is_read, created_at DESC")
    }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    // 타입별 상세 데이터 (JSON 형태로 저장)
    @Column(name = "detail_data", columnDefinition = "TEXT")
    private String detailData;

    // 앱에서 표시할 아이콘 타입
    @Column(name = "icon_type", length = 30)
    private String iconType;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    // 알림을 읽음 상태로 변경
    public void markAsRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now();
    }

    // 알림이 읽히지 않았는지 확인
    public boolean isUnread() {
        return !isRead;
    }

    // 특정 회원의 알림인지 확인
    public boolean isOwnedBy(Long memberId) {
        return this.member.getMemberId().equals(memberId);
    }
}

