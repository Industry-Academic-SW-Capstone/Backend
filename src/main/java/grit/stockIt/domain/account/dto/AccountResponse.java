package grit.stockIt.domain.account.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import grit.stockIt.domain.account.entity.Account;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
    private Boolean isDefault;

    public static AccountResponse from(Account a) {
        return AccountResponse.builder()
                .accountId(a.getAccountId())
                .memberId(a.getMember() != null ? a.getMember().getMemberId() : null)
                .contestId(a.getContest() != null ? a.getContest().getContestId() : null)
                .accountName(a.getAccountName())
                .cash(a.getCash())
                .isDefault(a.getIsDefault())
                .build();
    }
}
