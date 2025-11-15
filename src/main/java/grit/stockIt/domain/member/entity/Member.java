package grit.stockIt.domain.member.entity;

import grit.stockIt.global.common.BaseEntity;
import grit.stockIt.domain.auth.entity.KakaoToken;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Member extends BaseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "name", nullable = false, length = 20)
    private String name;

    @Column(name = "profile_image", length = 255)
    private String profileImage;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AuthProvider provider;

    @Column(name = "email", unique = true, nullable = false, length = 255)
    private String email;

    @Column(name = "password")
    private String password;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "kakaotoken_id")
    private KakaoToken kakaoToken;

    // FCM 토큰 관련 필드
    @Column(name = "fcm_token", length = 1000)
    private String fcmToken;

    // 알림 설정 필드
    @Column(name = "execution_notification_enabled", nullable = false)
    @Builder.Default
    private boolean executionNotificationEnabled = true;

    /**
     * 카카오 토큰 업데이트 또는 생성
     */
    public void updateKakaoToken(KakaoToken kakaoToken) {
        this.kakaoToken = kakaoToken;
        if (kakaoToken != null) {
            kakaoToken.setMember(this);  // 양방향 관계 설정
        }
    }

    public void updateFcmToken(String fcmToken) {
        this.fcmToken = fcmToken;
    }

    public void removeFcmToken() {
        this.fcmToken = null;
    }

    public boolean hasFcmToken() {
        return fcmToken != null && !fcmToken.isBlank();
    }

    // 체결 알림 설정 메서드
    public void enableExecutionNotification() {
        this.executionNotificationEnabled = true;
    }

    public void disableExecutionNotification() {
        this.executionNotificationEnabled = false;
    }

    public boolean isExecutionNotificationEnabled() {
        return executionNotificationEnabled;
    }
}