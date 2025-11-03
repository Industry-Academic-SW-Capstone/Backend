package grit.stockIt.domain.auth.entity;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "kakao_token")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class KakaoToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "kakaotoken_id")
    private Long kakaoTokenId;

    @Column(name = "access_token", nullable = false, length = 500)
    private String accessToken;

    @Column(name = "access_token_expires_in")
    private LocalDateTime accessTokenExpiresIn;

    @Column(name = "refresh_token", length = 500)
    private String refreshToken;

    @Column(name = "refresh_token_expires_in")
    private LocalDateTime refreshTokenExpiresIn;

    @OneToOne(mappedBy = "kakaoToken", fetch = FetchType.LAZY)
    private Member member;

    /**
     * 양방향 관계 설정을 위한 메서드
     */
    public void setMember(Member member) {
        this.member = member;
    }

    /**
     * 토큰 업데이트
     */
    public void updateToken(String accessToken, LocalDateTime accessTokenExpiry) {
        this.accessToken = accessToken;
        this.accessTokenExpiresIn = accessTokenExpiry;
    }

    /**
     * 리프레시 토큰까지 포함한 전체 업데이트
     */
    public void updateAllTokens(String accessToken, LocalDateTime accessTokenExpiry,
                                String refreshToken, LocalDateTime refreshTokenExpiry) {
        this.accessToken = accessToken;
        this.accessTokenExpiresIn = accessTokenExpiry;
        this.refreshToken = refreshToken;
        this.refreshTokenExpiresIn = refreshTokenExpiry;
    }
}