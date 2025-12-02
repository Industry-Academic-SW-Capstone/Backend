package grit.stockIt.domain.member.entity;

// [병합] 양쪽의 import 문을 모두 포함시킵니다.
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.mission.entity.MissionProgress;
import grit.stockIt.domain.title.entity.MemberTitle;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.global.common.BaseEntity;
import grit.stockIt.domain.auth.entity.KakaoToken;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

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
     * 팀원의 'Account' 엔티티와 N:1 관계 (Order.java 참조)
     * Member는 여러 Account를 가질 수 있음 (Contest별)
     */
    @OneToMany(mappedBy = "member")
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();

    /**
     * 회원이 보유한 칭호 목록 (1:N)
     */
    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MemberTitle> memberTitles = new ArrayList<>();

    /**
     * 회원의 미션 진행도 목록 (1:N)
     */
    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<MissionProgress> missionProgresses = new ArrayList<>();
    // --- ⬆️ [병합 2] 완료 ⬆️ ---


    // 2단계 인증 사용 여부
    @Column(name = "two_factor_enabled", nullable = false)
    @Builder.Default
    private boolean twoFactorEnabled = false;

    // 기타 알림 동의 여부
    @Column(name = "notification_agreement", nullable = false)
    @Builder.Default
    private boolean notificationAgreement = false;

    // 튜토리얼 완료 여부들
    @Column(name = "main_tutorial_completed", nullable = false)
    @Builder.Default
    private boolean mainTutorialCompleted = false;

    @Column(name = "securities_depth_tutorial_completed", nullable = false)
    @Builder.Default
    private boolean securitiesDepthTutorialCompleted = false;

    @Column(name = "stock_detail_tutorial_completed", nullable = false)
    @Builder.Default
    private boolean stockDetailTutorialCompleted = false;

    // 설문조사 완료 여부
    @Column(name = "survey_completed", nullable = false)
    @Builder.Default
    private boolean surveyCompleted = false;

    /**
     * [신규] 사용자가 선택한 대표 칭호 (없을 수도 있음 -> nullable)
     * 여러 멤버가 동일한 Title을 대표 칭호로 설정할 수 있으므로 ManyToOne
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "representative_title_id")
    private Title representativeTitle;

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

    // 프로필 정보 업데이트
    public void updateProfile(String name, String profileImage) {
        if (name != null) this.name = name;
        if (profileImage != null) this.profileImage = profileImage;
    }

    public void setTwoFactorEnabled(boolean enabled) {
        this.twoFactorEnabled = enabled;
    }

    public void setNotificationAgreement(boolean agreed) {
        this.notificationAgreement = agreed;
    }

    public boolean isTwoFactorEnabled() {
        return twoFactorEnabled;
    }

    public boolean isNotificationAgreement() {
        return notificationAgreement;
    }

    public void setMainTutorialCompleted(boolean completed) {
        this.mainTutorialCompleted = completed;
    }

    public void setSecuritiesDepthTutorialCompleted(boolean completed) {
        this.securitiesDepthTutorialCompleted = completed;
    }

    public void setStockDetailTutorialCompleted(boolean completed) {
        this.stockDetailTutorialCompleted = completed;
    }

    public boolean isMainTutorialCompleted() {
        return mainTutorialCompleted;
    }

    public boolean isSecuritiesDepthTutorialCompleted() {
        return securitiesDepthTutorialCompleted;
    }

    public boolean isStockDetailTutorialCompleted() {
        return stockDetailTutorialCompleted;
    }

    public void setSurveyCompleted(boolean completed) {
        this.surveyCompleted = completed;
    }

    public boolean isSurveyCompleted() {
        return surveyCompleted;
    }

    /**
     * [신규] 칭호 추가 (보상 지급 시 호출됨)
     */
    public void addMemberTitle(MemberTitle memberTitle) {
        this.memberTitles.add(memberTitle);
        if (memberTitle != null && memberTitle.getMember() != this) {
            memberTitle.setMember(this); // 양방향 관계 설정
        }
    }

    /**
     * [신규] 미션 진행도 추가 (미션 첫 활성화 시)
     */
    public void addMissionProgress(MissionProgress progress) {
        this.missionProgresses.add(progress);
        if (progress != null && progress.getMember() != this) {
            progress.setMember(this); // 양방향 관계 설정
        }
    }
    // --- ⬆️ [병합 4] 완료 ⬆️ ---

    /**
     * [신규] 대표 칭호 변경 메서드
     */
    public void updateRepresentativeTitle(Title title) {
        this.representativeTitle = title;
    }
}