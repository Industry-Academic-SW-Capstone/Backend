package grit.stockIt.domain.member.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.title.dto.TitleResponse;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class MemberResponse {

    private Long memberId;
    private String email;
    private String name;
    private String profileImage;
    private String provider; // LOCAL, KAKAO
    private LocalDateTime createdAt;
    private boolean twoFactorEnabled;
    private boolean notificationAgreement;
        private boolean mainTutorialCompleted;
        private boolean securitiesDepthTutorialCompleted;
        private boolean stockDetailTutorialCompleted;

    // --- [수정] 칭호 및 잔액 필드 추가 ---
    private List<TitleResponse> titles;
    private Long representativeTitleId;
    private BigDecimal balance;

    // 보유 칭호는 부가 서비스의 데이터라 호출하는 쪽이 넘긴다.
    public static MemberResponse from(Member member, List<TitleResponse> titles) {

        // --- [수정] 로직 추가: 기본 계좌 잔액 찾기 ---
        // Member 엔티티의 accounts 리스트와 Account의 isDefault 필드를 사용
        BigDecimal currentBalance = member.getAccounts().stream()
                .filter(Account::getIsDefault) // 'isDefault'가 true인 계좌를 찾음
                .findFirst() // 첫 번째 기본 계좌
                .map(Account::getCash) // 해당 계좌의 잔액(cash)을 가져옴
                .orElse(BigDecimal.ZERO); // 기본 계좌가 없으면 0으로 표시


        return MemberResponse.builder()
                .memberId(member.getMemberId())
                .email(member.getEmail())
                .name(member.getName())
                .profileImage(member.getProfileImage())
                .provider(member.getProvider() != null ? member.getProvider().name() : null)
                .createdAt(member.getCreatedAt())
                .titles(titles)
                .representativeTitleId(member.getRepresentativeTitleId())
                .balance(currentBalance)
                .twoFactorEnabled(member.isTwoFactorEnabled())
                .notificationAgreement(member.isNotificationAgreement())
                                .mainTutorialCompleted(member.isMainTutorialCompleted())
                                .securitiesDepthTutorialCompleted(member.isSecuritiesDepthTutorialCompleted())
                                .stockDetailTutorialCompleted(member.isStockDetailTutorialCompleted())
                .build();
    }
}