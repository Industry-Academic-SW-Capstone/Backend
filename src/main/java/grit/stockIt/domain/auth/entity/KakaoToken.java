package grit.stockIt.domain.auth.entity;

import grit.stockIt.global.common.BaseEntity;
import grit.stockIt.domain.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "kakao_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KakaoToken extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "kakaotoken_id")
    private Long kakaoTokenId;

    @Column(name = "access_token", length = 255)
    private String accessToken;

    @Column(name = "access_token_expires_in")
    private LocalDateTime accessTokenExpiresIn;

    @Column(name = "refresh_token", length = 255)
    private String refreshToken;

    @Column(name = "refresh_token_expires_in")
    private LocalDateTime refreshTokenExpiresIn;

    @OneToOne(mappedBy = "kakaoToken")
    private Member member;

    @Builder
    public KakaoToken(String accessToken, LocalDateTime accessTokenExpiresIn,
                      String refreshToken, LocalDateTime refreshTokenExpiresIn) {
        this.accessToken = accessToken;
        this.accessTokenExpiresIn = accessTokenExpiresIn;
        this.refreshToken = refreshToken;
        this.refreshTokenExpiresIn = refreshTokenExpiresIn;
    }

    public void updateToken(String accessToken, LocalDateTime accessTokenExpiresIn) {
        this.accessToken = accessToken;
        this.accessTokenExpiresIn = accessTokenExpiresIn;
    }
}