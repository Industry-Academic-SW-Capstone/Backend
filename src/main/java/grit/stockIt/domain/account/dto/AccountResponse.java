package grit.stockIt.domain.account.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import grit.stockIt.domain.account.entity.Account;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AccountResponse {
    private Long accountId;
    private Long memberId;
    private Long contestId;
    private String accountName;
    private BigDecimal cash;
    private BigDecimal holdAmount;
    private BigDecimal availableCash;
    private Boolean isDefault;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public static AccountResponse from(Account a) {
        return AccountResponse.builder()
                .accountId(a.getAccountId())
                .memberId(a.getMember() != null ? a.getMember().getMemberId() : null)
                .contestId(a.getContest() != null ? a.getContest().getContestId() : null)
                .accountName(a.getAccountName())
                .cash(a.getCash())
                .holdAmount(a.getHoldAmount())
                .availableCash(a.getAvailableCash())
                .isDefault(a.getIsDefault())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .deletedAt(a.getDeletedAt())
                .build();
    }
}
