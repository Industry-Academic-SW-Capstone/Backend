package grit.stockIt.domain.member.entity;

// [병합] 양쪽의 import 문을 모두 포함시킵니다.
import grit.stockIt.domain.account.entity.Account;
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

    /**
     * 팀원의 'Account' 엔티티와 N:1 관계 (Order.java 참조)
     * Member는 여러 Account를 가질 수 있음 (Contest별)
     */
    @OneToMany(mappedBy = "member")
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();
    // --- [병합 2] 완료 ---


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

    // 대표 칭호. 칭호는 부가 서비스가 가지므로 번호와 이름을 복사해 둔다 — 프로필·랭킹이 부가에 묻지 않고 보여 준다.
    @Column(name = "representative_title_id")
    private Long representativeTitleId;

    @Column(name = "representative_title_name", length = 100)
    private String representativeTitleName;

    /**
     * 카카오 토큰 업데이트 또는 생성
     */
    public void updateKakaoToken(KakaoToken kakaoToken) {
        this.kakaoToken = kakaoToken;
        if (kakaoToken != null) {
            kakaoToken.setMember(this);  // 양방향 관계 설정
        }
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
     * [신규] 대표 칭호 변경 메서드
     */
    public void updateRepresentativeTitle(Long titleId, String titleName) {
        this.representativeTitleId = titleId;
        this.representativeTitleName = titleName;
    }
}