package grit.stockIt.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원의 푸시 수신 설정. 어느 기기로 보낼지(FCM 토큰), 체결 알림을 받을지는 알림이 가질 데이터라 회원 테이블이 아니라 여기 둔다.
// 행이 없는 회원은 토큰 없음·체결 알림 켬으로 본다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "notification_setting")
public class NotificationSetting {

    @Id
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "fcm_token", length = 1000)
    private String fcmToken;

    @Column(name = "execution_notification_enabled", nullable = false)
    private boolean executionNotificationEnabled = true;

    public static NotificationSetting defaultFor(Long memberId) {
        NotificationSetting setting = new NotificationSetting();
        setting.memberId = memberId;
        return setting;
    }

    public void updateFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    public void removeFcmToken() {
        this.fcmToken = null;
    }

    public void updateExecutionNotification(boolean enabled) {
        this.executionNotificationEnabled = enabled;
    }

    public boolean hasFcmToken() {
        return fcmToken != null && !fcmToken.isBlank();
    }
}
